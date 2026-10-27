package com.xaepudin.midtrack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the notification action buttons:
 *   - master toggle;
 *   - mode cycling — of the *playing app's own rule* when it has one, so the
 *     button always edits the rule you are actually hearing;
 *   - jump now, which is forwarded to the service (it is the only thing
 *     holding the MediaController).
 *
 * The service reads prefs fresh on every seek, so no restart is needed — only
 * the notification itself is reposted to reflect the new labels.
 */
class MidtrackActionReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> Prefs.setEnabled(ctx, !Prefs.isEnabled(ctx))

            ACTION_CYCLE_MODE -> {
                val pkg = Prefs.activePkg(ctx)
                if (pkg != null && Prefs.hasAppRule(ctx, pkg)) {
                    // This app has a rule of its own — cycle that one.
                    val cfg = Prefs.config(ctx, pkg)
                    Prefs.setAppRule(ctx, pkg, cfg.copy(mode = SeekLogic.nextMode(cfg.mode)))
                } else {
                    Prefs.setMode(ctx, SeekLogic.nextMode(Prefs.mode(ctx)))
                }
            }

            MidtrackService.ACTION_JUMP_NOW -> {
                // The service holds the MediaController and is alive whenever
                // this notification exists, so it listens for this in-process.
                // (Starting a foreground service from the background is
                // restricted on Android 12+, and there is nothing to start.)
                ctx.sendBroadcast(
                    Intent(MidtrackService.ACTION_JUMP_NOW).setPackage(ctx.packageName)
                )
                return
            }
        }
        Notifications.repost(ctx)
        // Let an open MainActivity re-render immediately — don't wait for onResume.
        ctx.sendBroadcast(Intent(ACTION_PREFS_CHANGED).setPackage(ctx.packageName))
    }

    companion object {
        const val ACTION_TOGGLE = "com.xaepudin.midtrack.TOGGLE"
        const val ACTION_CYCLE_MODE = "com.xaepudin.midtrack.CYCLE_MODE"
        const val ACTION_PREFS_CHANGED = "com.xaepudin.midtrack.PREFS_CHANGED"
    }
}
