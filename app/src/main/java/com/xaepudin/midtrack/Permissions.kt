package com.xaepudin.midtrack

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * One place for the two notification-related permissions and the disclosure
 * Google Play expects before sending the user to notification access.
 */
object Permissions {
    const val REQUEST_POST_NOTIFICATIONS = 100

    fun hasNotificationAccess(ctx: Context): Boolean {
        // getEnabledNotificationListeners() is @SystemApi — read the secure setting.
        val flat = Settings.Secure.getString(
            ctx.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return flat.split(":").any { it.startsWith("${ctx.packageName}/") }
    }

    fun notificationAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    /** App-level notifications, including the runtime permission on Android 13+. */
    fun notificationsEnabled(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun requestPostNotifications(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS,
            )
        }
    }

    fun appNotificationSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

    /**
     * Prominent disclosure before the system notification-access screen. The
     * copy says exactly what the app reads: the active media session, not the
     * contents of notifications.
     */
    fun showNotificationAccessDisclosure(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.notification_access_title)
            .setMessage(R.string.notification_access_message)
            .setPositiveButton(R.string.notification_access_continue) { _, _ ->
                try {
                    activity.startActivity(notificationAccessIntent())
                } catch (e: Exception) {
                    // heavily modified builds may not expose the screen
                }
            }
            .setNegativeButton(R.string.notification_access_cancel, null)
            .show()
    }
}
