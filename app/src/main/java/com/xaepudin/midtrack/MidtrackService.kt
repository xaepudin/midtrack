package com.xaepudin.midtrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import android.widget.Toast

/**
 * Watches the active media session (legal, via notification-access-gated
 * getActiveSessions) and jumps playback to the configured spot on track change.
 *
 * Same modes and guards across every surface: no backward yank, no landing on
 * the end, auto-next option, dedup per track.
 */
class MidtrackService : NotificationListenerService() {

    companion object {
        private const val TAG = "Midtrack"
        private const val SEEK_DELAY_MS = 300L
        private const val DEDUP_WINDOW_MS = 30_000L
        private const val PAST_MARGIN_MS = 5_000L

        /** Notification button: apply the rule to the track playing right now. */
        const val ACTION_JUMP_NOW = "com.xaepudin.midtrack.JUMP_NOW"
    }

    /**
     * "Jump now" arrives as an in-process broadcast instead of a started
     * service: the notification only exists while this service runs, and
     * starting a foreground service from the background is restricted on
     * Android 12+. Nothing to start, nothing to restrict.
     */
    private val jumpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_JUMP_NOW) jumpNow()
        }
    }
    private var jumpReceiverRegistered = false

    private val handler = Handler(Looper.getMainLooper())
    private var mediaSessionManager: MediaSessionManager? = null
    private val sessionsListener = object : MediaSessionManager.OnActiveSessionsChangedListener {
        override fun onActiveSessionsChanged(controllers: List<MediaController>?) {
            attachBestSession()
        }
    }
    private var controller: MediaController? = null

    // whether the service is alive and should keep its notification pinned
    private var running = false
    private val rePinDelayMs = 250L
    private val pinIntervalMs = 10 * 60 * 1000L
    private val pinRunnable = Runnable { rePinNotification(0) }

    // per-track state
    private var lastMediaId: String? = null
    private var lastTitle: String? = null
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    private var lastSeekMediaId: String? = null
    private var lastSeekAtMs = 0L
    private var pendingSeek: Runnable? = null
    private var hasAttachedOnce = false
    private var posBeforeChangeMs = 0L
    private var attachBaselineAtMs = 0L
    private var attachBaselineTitle: String? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (metadata == null) return
            // Snapshot the position BEFORE the change — it belongs to the old
            // track and is the reference for detecting stale positions later.
            posBeforeChangeMs = lastPositionMs
            val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            if (durationMs <= 0) {
                // Some players post metadata before the duration is known —
                // don't baseline yet; the next update will trigger the seek.
                Log.d(TAG, "metadata without duration (${metadata.getString(MediaMetadata.METADATA_KEY_TITLE)}) — waiting")
                return
            }
            val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: return
            if (mediaId == lastMediaId) return // same track repeating — no jump
            // Right after attach, apps often re-post metadata with a fuller
            // mediaId — don't treat that as a track change.
            val titleNow = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            if (System.currentTimeMillis() - attachBaselineAtMs < 3000 &&
                titleNow != null && titleNow == attachBaselineTitle
            ) {
                Log.d(TAG, "same title right after attach — absorbing ($titleNow)")
                lastMediaId = mediaId
                lastTitle = titleNow
                return
            }
            lastMediaId = mediaId
            lastTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: getString(R.string.song_fallback)
            // The notification carries what is playing, so it follows the track.
            controller?.packageName?.let { Prefs.setNowPlaying(this@MidtrackService, it, lastTitle) }
            Notifications.repost(this@MidtrackService)
            val autoNext = SeekLogic.isAutoNext(lastPositionMs, lastDurationMs)
            lastDurationMs = durationMs
            Log.d(TAG, "track change: $lastTitle (dur ${durationMs / 1000}s, autoNext=$autoNext)")
            scheduleSeek(mediaId, durationMs, autoNext)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state != null) lastPositionMs = state.position
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        startAsForeground()
        watchSessions()
        schedulePinLoop()
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        running = true
        startAsForeground()
        watchSessions()
        schedulePinLoop()
        Log.d(TAG, "listener connected — watching for media sessions")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Access was revoked (or the system unbound us). There is nothing left
        // to listen to, so do not leave a foreground notification claiming
        // otherwise. The system reconnects the listener if access is restored.
        Log.d(TAG, "listener disconnected — stopping")
        running = false
        handler.removeCallbacks(pinRunnable)
        unregisterJumpReceiver()
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * The user swiped the app away from Recents. Keep the service and its
     * notification alive: repost the notification (it stays ongoing) and make
     * sure session watching is still attached. Never stopSelf() here — the
     * whole point of the foreground service is that it keeps running.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "task removed — keeping service and notification alive")
        rePinNotification(0)
        watchSessions()
    }

    /**
     * Some devices (Samsung One UI's "ongoing dismiss" policy, Android 16
     * behavior changes) let the user swipe away even ongoing foreground-service
     * notifications. Since this app has notification access, we hear about the
     * removal instantly and put the notification right back — that's what makes
     * it truly persistent.
     */
    override fun onNotificationRemoved(
        sbn: android.service.notification.StatusBarNotification,
        rankingMap: android.service.notification.NotificationListenerService.RankingMap?,
        reason: Int
    ) {
        if (sbn.packageName != packageName) return
        if (sbn.id != Notifications.NOTIF_ID) return
        Log.d(TAG, "notification removed (reason=$reason) — re-pinning")
        rePinNotification(rePinDelayMs)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(pinRunnable)
        unregisterJumpReceiver()
        teardown()
        super.onDestroy()
    }

    /**
     * Reposts the notification while the service is alive AND the feature is
     * enabled AND the user wants the notification always shown. A short delay
     * lets a swipe animation finish so the notification visibly "snaps back".
     * Also runs periodically as a safety net in case a device dismisses the
     * notification through a path we can't observe.
     *
     * If Midtrack is OFF, or the user turned off "always show notification",
     * the notification is deliberately allowed to disappear.
     */
    private fun rePinNotification(delayMs: Long) {
        handler.postDelayed({
            if (!running || !Prefs.isEnabled(this) || !Prefs.repinEnabled(this)) return@postDelayed
            try {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(Notifications.NOTIF_ID, Notifications.build(this))
            } catch (e: Exception) {
                Log.w(TAG, "re-pin failed: ${e.message}")
            }
        }, delayMs)
    }

    private fun schedulePinLoop() {
        handler.removeCallbacks(pinRunnable)
        handler.postDelayed(pinRunnable, pinIntervalMs)
    }

    private fun teardown() {
        mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        controller?.unregisterCallback(callback)
        controller = null
        pendingSeek?.let { handler.removeCallbacks(it) }
        pendingSeek = null
        hasAttachedOnce = false
        Prefs.clearNowPlaying(this)
    }

    private fun startAsForeground() {
        try {
            // The manifest declares foregroundServiceType="specialUse", so the
            // two-argument call picks the correct type on every supported API.
            startForeground(Notifications.NOTIF_ID, Notifications.build(this))
        } catch (e: Exception) {
            Log.w(TAG, "foreground start failed: ${e.message}")
        }
    }

    /** Registered once, however many times the service is (re)started. */
    private fun registerJumpReceiver() {
        if (jumpReceiverRegistered) return
        val filter = IntentFilter(ACTION_JUMP_NOW)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(jumpReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(jumpReceiver, filter)
            }
            jumpReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "jump receiver not registered: ${e.message}")
        }
    }

    private fun unregisterJumpReceiver() {
        if (!jumpReceiverRegistered) return
        jumpReceiverRegistered = false
        try {
            unregisterReceiver(jumpReceiver)
        } catch (e: Exception) {
            // already gone with the process — nothing to undo
        }
    }

    private fun watchSessions() {
        registerJumpReceiver()
        try {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            mediaSessionManager = msm
            val comp = ComponentName(this, MidtrackService::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsListener, comp, handler)
            attachBestSession()
        } catch (e: Exception) {
            Log.w(TAG, "watchSessions failed: ${e.message}")
        }
    }

    private fun attachBestSession() {
        try {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = ComponentName(this, MidtrackService::class.java)
            val sessions = msm.getActiveSessions(comp) ?: emptyList()
            val target = pickBestSession(sessions)
            if (target === controller) return
            val dropped = controller?.packageName
            controller?.unregisterCallback(callback)
            controller = target
            if (target != null) {
                // snapshot the OLD session's state before the new one overwrites it
                val prevPos = lastPositionMs
                val prevDur = lastDurationMs
                val wasAttached = hasAttachedOnce
                hasAttachedOnce = true

                target.registerCallback(callback, handler)
                val state = target.playbackState
                state?.let { lastPositionMs = it.position }
                val pos = state?.position ?: 0L
                val meta = target.metadata
                if (meta != null) {
                    val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                    val mediaId = meta.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                        ?: title
                    val dur = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
                    lastMediaId = mediaId
                    lastDurationMs = dur
                    // remember the baseline so a metadata re-post with the same title
                    // right after attach doesn't look like a track change
                    attachBaselineAtMs = System.currentTimeMillis()
                    attachBaselineTitle = title
                    // Feed the notification's "▶ <track>" line.
                    Prefs.setNowPlaying(this, target.packageName, title)
                    Notifications.repost(this)

                    val freshTrack = pos <= 5000 || (dur > 0 && pos > dur)
                    if (wasAttached && freshTrack && mediaId != null && dur > 0) {
                        // Apps often recreate their session exactly at a track
                        // change. A re-attach with a just-started (or clearly
                        // stale) position IS the change — treat it as one
                        // (dedup guards against double seeks).
                        val autoNext = SeekLogic.isAutoNext(prevPos, prevDur)
                        Log.d(TAG, "session recreated for a fresh track ($title) — evaluating a jump (autoNext=$autoNext)")
                        scheduleSeek(mediaId, dur, autoNext)
                    } else {
                        // first attach (app/service start): adopt current track as
                        // baseline — no jump for whatever is already playing
                        Log.d(TAG, "attached to media session (${target.packageName}) — current: $title (baseline, no seek)")
                    }
                }
            } else {
                if (Prefs.activePkg(this) != null) {
                    // Nothing is playing any more — drop the stale track line.
                    Prefs.clearNowPlaying(this)
                    Notifications.repost(this)
                }
                if (dropped != null) {
                    Log.d(TAG, "detached from $dropped — no selected app is playing")
                } else {
                    Log.d(TAG, "no media session right now — play something in any music app")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "notification access not granted — session watch blocked")
        } catch (e: Exception) {
            Log.w(TAG, "attach failed: ${e.message}")
        }
    }

    /**
     * Picks the session most likely to be "the one playing now": prefer a
     * PLAYING session, then one with metadata, then the most recently updated.
     * Sessions from apps outside the whitelist are skipped entirely — that's
     * what lets you seek in YouTube Music while leaving YouTube alone.
     */
    private fun pickBestSession(sessions: List<MediaController>): MediaController? {
        val mode = Prefs.appScope(this)
        val selected = Prefs.selectedApps(this)
        var best: MediaController? = null
        var bestScore = Long.MIN_VALUE
        var blockedPkg: String? = null
        for (c in sessions) {
            if (c.packageName == packageName) continue
            // Learn every player we come across so the picker can offer it.
            Prefs.rememberSeenApp(this, c.packageName)
            if (!AppScope.allows(mode, selected, c.packageName)) {
                blockedPkg = c.packageName
                continue
            }
            val state = c.playbackState
            val meta = c.metadata
            if (state == null && meta == null) continue
            var score = 0L
            if (state?.state == PlaybackState.STATE_PLAYING) score += 1_000_000L
            if (meta != null) score += 100_000L
            score += state?.lastPositionUpdateTime ?: 0L
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        if (best == null && blockedPkg != null) {
            Log.d(TAG, "$blockedPkg is playing but not selected in Apps — ignoring")
        }
        return best
    }

    /**
     * Re-checked on every track change (and again right before the seek) so a
     * whitelist edit takes effect immediately, even for a session we are
     * already attached to.
     */
    private fun controllerAllowed(): Boolean {
        val pkg = controller?.packageName ?: return false
        return Prefs.isAppAllowed(this, pkg)
    }

    private fun scheduleSeek(mediaId: String, durationMs: Long, autoNext: Boolean) {
        if (!Prefs.isEnabled(this)) return
        if (!controllerAllowed()) {
            Log.d(TAG, "track change in ${controller?.packageName} — app not selected, no jump")
            return
        }
        // The playing app's own rule when it has one, the global default otherwise.
        val cfg = Prefs.config(this, controller?.packageName)
        if (!cfg.seekOnAutoNext && autoNext) {
            Log.d(TAG, "no jump: song ended on its own and this rule only seeks manual skips")
            return
        }
        SeekLogic.skipReason(durationMs, cfg)?.let {
            Log.d(TAG, "no jump: $it")
            return
        }
        pendingSeek?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { doSeek(mediaId, durationMs, autoNext, cfg) }
        pendingSeek = runnable
        handler.postDelayed(runnable, SEEK_DELAY_MS)
    }

    private fun doSeek(mediaId: String, durationMs: Long, autoNext: Boolean, cfg: SeekLogic.Config) {
        if (!controllerAllowed()) return
        val target = SeekLogic.computeTarget(durationMs, cfg) ?: return
        if (mediaId == lastSeekMediaId && System.currentTimeMillis() - lastSeekAtMs < DEDUP_WINDOW_MS) return
        if (!autoNext) {
            // "already past the spot" guard — but only trust the position when
            // it clearly belongs to the NEW track:
            //  - natural end: the position is still the previous track's end
            //  - paused skip: the position never resets (still the old pause point)
            //  - playing skip: the position resets to ~0, so it's trustworthy
            val pos = controller?.playbackState?.position ?: 0L
            val stalePos = pos > durationMs || Math.abs(pos - posBeforeChangeMs) <= 500
            if (!stalePos && pos > target + PAST_MARGIN_MS) {
                Log.d(TAG, "skip: already at ${pos / 1000}s past target ${target / 1000}s")
                return
            }
        }
        controller?.transportControls?.seekTo(target)
        lastSeekMediaId = mediaId
        lastSeekAtMs = System.currentTimeMillis()
        Prefs.setLastJump(this, lastTitle ?: getString(R.string.song_fallback), target)
        Log.d(TAG, "SEEK to ${target / 1000}.${(target % 1000) / 100}s (dur ${durationMs / 1000}s, pos ${(controller?.playbackState?.position ?: 0L) / 1000}s)")
    }

    /**
     * The notification's "Jump now" button: applies the rule to whatever is
     * playing at this moment, even though the track was already seeked (or is
     * already past the spot). The minimum-length guardrail is deliberately
     * bypassed — pressing the button is an explicit "do it anyway" — but a
     * track that never reports its length is still refused, because there is
     * no target we could pick that stays clear of the end.
     */
    private fun jumpNow() {
        if (!Prefs.isEnabled(this)) {
            Toast.makeText(this, R.string.toast_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        val c = controller
        val meta = c?.metadata
        if (c == null || meta == null) {
            toast(getString(R.string.toast_nothing_playing))
            return
        }
        if (!Prefs.isAppAllowed(this, c.packageName)) {
            toast(
                getString(
                    R.string.toast_app_not_selected,
                    Notifications.appLabel(this, c.packageName),
                )
            )
            return
        }
        val durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val cfg = Prefs.config(this, c.packageName).copy(minDurationSec = 0)
        val target = SeekLogic.computeTarget(durationMs, cfg)
        if (target == null) {
            toast(SeekLogic.skipReason(durationMs, cfg) ?: getString(R.string.toast_nothing_to_jump))
            return
        }
        c.transportControls?.seekTo(target)
        lastSeekMediaId = meta.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?: meta.getString(MediaMetadata.METADATA_KEY_TITLE)
        lastSeekAtMs = System.currentTimeMillis()
        Prefs.setLastJump(
            this,
            meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: getString(R.string.song_fallback),
            target,
        )
        Log.d(TAG, "SEEK (manual) to ${target / 1000}s (dur ${durationMs / 1000}s)")
    }

    private fun toast(msg: String) {
        Log.d(TAG, "manual jump refused: $msg")
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // some builds block toasts from the background — the log still has it
        }
    }
}
