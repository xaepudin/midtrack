package com.xaepudin.midtrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build

/**
 * Builds the foreground-service notification:
 *
 *   Midtrack
 *   On · 20% · 1 app            (or "On · 15s · YouTube only" with a rule)
 *   ▶ Blinding Lights            (only while a session is attached)
 *   [Turn off]  [Mode: 20%]  [Jump now]
 *
 * Everything is read from prefs, so any caller — the service, the action
 * receiver, the Quick Settings tile, the open activity — can rebuild exactly
 * the same notification with [build] / [repost].
 */
object Notifications {
    const val CHANNEL_ID = "midtrack"
    const val NOTIF_ID = 1

    fun build(ctx: Context): Notification {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = ctx.getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
        )
        val enabled = Prefs.isEnabled(ctx)
        val activePkg = Prefs.activePkg(ctx)
        // The rule that is in force right now: the playing app's own, or the default.
        val cfg = Prefs.config(ctx, activePkg)
        val ruleLabel = SeekLogic.modeLabel(cfg)
        val customHere = activePkg != null && Prefs.hasAppRule(ctx, activePkg)
        val appName = activePkg?.let { appLabel(ctx, it) }

        val notification = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(statusText(ctx, enabled, ruleLabel, customHere, appName))
            // Tapping the (collapsed or expanded) notification opens the app.
            .setContentIntent(openApp(ctx))
            // Pinned only when the user asked for it. With the default off,
            // the notification can be dismissed like any other.
            .setOngoing(Prefs.repinEnabled(ctx))
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT >= 31) {
            // Show immediately on every (re)start instead of being deferred.
            notification.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (enabled) {
            Prefs.nowTitle(ctx)?.let {
                notification.setSubText(ctx.getString(R.string.notification_now_playing, it))
            }
        }
        notification.addAction(
            action(
                ctx,
                R.drawable.ic_power,
                ctx.getString(
                    if (enabled) R.string.notification_turn_off else R.string.notification_turn_on
                ),
                MidtrackActionReceiver.ACTION_TOGGLE,
            )
        )
        notification.addAction(
            action(
                ctx,
                R.drawable.ic_mode,
                ctx.getString(R.string.notification_mode, ruleLabel),
                MidtrackActionReceiver.ACTION_CYCLE_MODE,
            )
        )
        if (enabled) {
            // Applies the rule to the song already playing — the answer to
            // "I turned it on mid-song and nothing happened".
            notification.addAction(
                action(
                    ctx,
                    R.drawable.ic_arrow_forward,
                    ctx.getString(R.string.notification_jump_now),
                    MidtrackService.ACTION_JUMP_NOW,
                )
            )
        }
        return notification.build()
    }

    /** Reposts the notification, but only while it is actually showing. */
    fun repost(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.activeNotifications.any { it.id == NOTIF_ID }) {
                nm.notify(NOTIF_ID, build(ctx))
            }
        } catch (e: Exception) {
            // notification permission missing or system busy — the next
            // service event will repost it anyway
        }
    }

    /** "On · 20% · 1 app", "On · 15s · YouTube only", "Off". */
    private fun statusText(
        ctx: Context,
        enabled: Boolean,
        ruleLabel: String,
        customHere: Boolean,
        appName: String?,
    ): String {
        if (!enabled) return ctx.getString(R.string.notification_status_off)
        if (customHere && appName != null) {
            return ctx.getString(R.string.notification_status_custom, ruleLabel, appName)
        }
        return ctx.getString(R.string.notification_status_default, ruleLabel, scopeSuffix(ctx))
    }

    /**
     * " · 1 app" when only whitelisted apps are seeked, so the shade shows at a
     * glance whether the filter is on (and warns when nothing is selected).
     */
    private fun scopeSuffix(ctx: Context): String {
        if (Prefs.appScope(ctx) != AppScope.SELECTED) return ""
        return when (val n = Prefs.selectedApps(ctx).size) {
            0 -> ctx.getString(R.string.notification_scope_none)
            1 -> ctx.getString(R.string.notification_scope_one)
            else -> ctx.getString(R.string.notification_scope_many, n)
        }
    }

    /** "Spotify" — falls back to the package name for removed apps. */
    fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    } catch (e: Exception) {
        pkg
    }

    /** One action button, built the non-deprecated way. */
    private fun action(ctx: Context, icon: Int, title: String, action: String): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(ctx, icon),
            title,
            pending(ctx, action),
        ).build()

    private fun pending(ctx: Context, action: String): PendingIntent {
        val intent = Intent(ctx, MidtrackActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openApp(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            ctx,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
