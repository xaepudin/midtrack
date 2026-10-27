package com.xaepudin.midtrack

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * First-run checklist. It explains and links the three things Midtrack
 * needs to work reliably, and offers a test jump. Everything here can be
 * revisited from Advanced settings.
 */
class SetupActivity : Activity() {

    private lateinit var accessIcon: ImageView
    private lateinit var accessStatus: TextView
    private lateinit var btnAccess: MaterialButton
    private lateinit var notificationIcon: ImageView
    private lateinit var notificationStatus: TextView
    private lateinit var btnNotifications: MaterialButton
    private lateinit var batteryIcon: ImageView
    private lateinit var batteryStatus: TextView
    private lateinit var btnBattery: MaterialButton
    private lateinit var btnTest: MaterialButton
    private lateinit var btnDone: MaterialButton

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_setup)

        val root = findViewById<View>(R.id.setupRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.setup_title)
        toolbar.navigationContentDescription = getString(R.string.back)
        toolbar.setNavigationOnClickListener { finish() }

        accessIcon = findViewById(R.id.accessIcon)
        accessStatus = findViewById(R.id.accessStatus)
        btnAccess = findViewById(R.id.btnAccessSetup)
        notificationIcon = findViewById(R.id.notificationIcon)
        notificationStatus = findViewById(R.id.notificationStatus)
        btnNotifications = findViewById(R.id.btnNotificationsSetup)
        batteryIcon = findViewById(R.id.batteryIcon)
        batteryStatus = findViewById(R.id.batteryStatus)
        btnBattery = findViewById(R.id.btnBatterySetup)
        btnTest = findViewById(R.id.btnTest)
        btnDone = findViewById(R.id.btnDone)

        btnAccess.setOnClickListener {
            if (Permissions.hasNotificationAccess(this)) {
                startActivity(Permissions.notificationAccessIntent())
            } else {
                Permissions.showNotificationAccessDisclosure(this)
            }
        }
        btnNotifications.setOnClickListener {
            if (notificationsNeedRequest()) {
                Prefs.setNotificationsRequested(this, true)
                Permissions.requestPostNotifications(this)
            } else {
                startActivity(Permissions.appNotificationSettingsIntent(this))
            }
        }
        btnBattery.setOnClickListener { DeviceSettings.openBatterySettings(this) }
        btnTest.setOnClickListener { testJump() }
        btnDone.setOnClickListener { confirmDone() }
        findViewById<MaterialButton>(R.id.btnSkip).setOnClickListener {
            finishSetup()
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Permissions.REQUEST_POST_NOTIFICATIONS) {
            render()
            if (!Permissions.notificationsEnabled(this)) {
                Toast.makeText(this, R.string.health_notifications_message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun render() {
        val access = Permissions.hasNotificationAccess(this)
        setStatus(
            accessIcon,
            accessStatus,
            access,
            R.string.setup_access_granted,
            R.string.setup_access_needed,
        )
        btnAccess.setText(
            if (access) R.string.setup_access_manage else R.string.setup_access_enable
        )

        val notifications = Permissions.notificationsEnabled(this)
        setStatus(
            notificationIcon,
            notificationStatus,
            notifications,
            R.string.setup_notifications_granted,
            R.string.setup_notifications_needed,
        )
        btnNotifications.setText(
            when {
                notifications -> R.string.setup_notifications_manage
                notificationsNeedRequest() -> R.string.setup_notifications_allow
                else -> R.string.setup_notifications_manage
            }
        )

        val battery = DeviceSettings.isIgnoringBatteryOptimizations(this)
        setStatus(
            batteryIcon,
            batteryStatus,
            battery,
            R.string.setup_battery_unrestricted,
            R.string.setup_battery_optimized,
        )
        btnBattery.setText(R.string.setup_battery_settings)

        btnTest.isEnabled = access
        btnTest.alpha = if (access) 1f else 0.5f
    }

    private fun notificationsNeedRequest(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !Prefs.notificationsRequested(this)

    private fun setStatus(
        icon: ImageView,
        text: TextView,
        ok: Boolean,
        okText: Int,
        badText: Int,
    ) {
        icon.setImageResource(if (ok) R.drawable.ic_check else R.drawable.ic_warning)
        icon.setColorFilter(getColor(if (ok) R.color.status_on else R.color.status_warning))
        text.setText(if (ok) okText else badText)
        text.setTextColor(getColor(if (ok) R.color.status_on else R.color.status_warning))
    }

    private fun testJump() {
        if (!Permissions.hasNotificationAccess(this)) {
            Toast.makeText(this, R.string.setup_test_no_access, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startForegroundService(Intent(this, MidtrackService::class.java))
        } catch (e: Exception) {
            // the service may already be running
        }
        handler.postDelayed({
            sendBroadcast(
                Intent(MidtrackService.ACTION_JUMP_NOW).setPackage(packageName)
            )
            Toast.makeText(this, R.string.setup_test_sent, Toast.LENGTH_SHORT).show()
        }, 700)
    }

    private fun confirmDone() {
        if (!Permissions.hasNotificationAccess(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setup_finish_title)
                .setMessage(R.string.setup_finish_message)
                .setPositiveButton(R.string.setup_finish_anyway) { _, _ -> finishSetup() }
                .setNegativeButton(R.string.setup_stay, null)
                .show()
        } else {
            finishSetup()
        }
    }

    private fun finishSetup() {
        Prefs.setSetupCompleted(this, true)
        finish()
    }
}
