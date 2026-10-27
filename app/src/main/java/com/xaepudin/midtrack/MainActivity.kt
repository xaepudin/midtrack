package com.xaepudin.midtrack

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.math.max
import kotlin.math.min

/**
 * The main control surface. It intentionally holds only the things used while
 * listening: the master switch, the jump mode and the app scope. Guardrails
 * and notification behavior live in [AdvancedActivity], and the app list lives
 * in [AppsActivity].
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var healthCard: View
    private lateinit var healthTitle: TextView
    private lateinit var healthMessage: TextView
    private lateinit var btnHealth: MaterialButton
    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var rowAbsolute: LinearLayout
    private lateinit var rowPercent: LinearLayout
    private lateinit var rowRandom: LinearLayout
    private lateinit var inputAbsolute: EditText
    private lateinit var inputPercent: EditText
    private lateinit var inputRandomMin: EditText
    private lateinit var inputRandomMax: EditText
    private lateinit var preview: TextView
    private lateinit var lastJump: TextView
    private lateinit var scopeSummary: TextView
    private lateinit var appScopeGroup: MaterialButtonToggleGroup
    private lateinit var selectedAppsLabel: TextView
    private lateinit var selectedAppsList: LinearLayout
    private lateinit var appListHint: TextView
    private lateinit var btnManageApps: MaterialButton
    private lateinit var cardAdvanced: View
    private lateinit var footerVersion: TextView
    private lateinit var privacyLink: TextView
    private lateinit var sourceLink: TextView

    /** Re-renders the UI when settings change elsewhere (notification, tile). */
    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MidtrackActionReceiver.ACTION_PREFS_CHANGED) render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Material You: pick up the wallpaper palette on Android 12+.
        DynamicColors.applyToActivityIfAvailable(this)
        Prefs.migrateDefaults(this)
        setContentView(R.layout.activity_main)

        // Edge-to-edge (enforced on Android 15+): keep the scroll content
        // clear of the status bar, gesture nav bar and soft keyboard.
        val scrollRoot = findViewById<View>(R.id.scrollRoot)
        ViewCompat.setOnApplyWindowInsetsListener(scrollRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(scrollRoot)

        status = findViewById(R.id.status)
        statusIcon = findViewById(R.id.statusIcon)
        healthCard = findViewById(R.id.healthCard)
        healthTitle = findViewById(R.id.healthTitle)
        healthMessage = findViewById(R.id.healthMessage)
        btnHealth = findViewById(R.id.btnHealth)
        switchEnabled = findViewById(R.id.switchEnabled)
        modeGroup = findViewById(R.id.modeGroup)
        rowAbsolute = findViewById(R.id.rowAbsolute)
        rowPercent = findViewById(R.id.rowPercent)
        rowRandom = findViewById(R.id.rowRandom)
        inputAbsolute = findViewById(R.id.inputAbsolute)
        inputPercent = findViewById(R.id.inputPercent)
        inputRandomMin = findViewById(R.id.inputRandomMin)
        inputRandomMax = findViewById(R.id.inputRandomMax)
        preview = findViewById(R.id.preview)
        lastJump = findViewById(R.id.lastJump)
        scopeSummary = findViewById(R.id.scopeSummary)
        appScopeGroup = findViewById(R.id.appScopeGroup)
        selectedAppsLabel = findViewById(R.id.selectedAppsLabel)
        selectedAppsList = findViewById(R.id.selectedAppsList)
        appListHint = findViewById(R.id.appListHint)
        btnManageApps = findViewById(R.id.btnManageApps)
        cardAdvanced = findViewById(R.id.cardAdvanced)
        footerVersion = findViewById(R.id.footerVersion)
        privacyLink = findViewById(R.id.privacyLink)
        sourceLink = findViewById(R.id.sourceLink)

        footerVersion.text = getString(
            R.string.footer_version,
            getString(R.string.app_name),
            BuildConfig.VERSION_NAME,
        )
        privacyLink.setOnClickListener { openUrl(getString(R.string.privacy_url)) }
        sourceLink.setOnClickListener { openUrl(getString(R.string.source_url)) }

        // First run: show the checklist on top of the main screen. Backing out
        // is allowed; the health card will keep offering it.
        if (savedInstanceState == null &&
            !Prefs.setupCompleted(this) &&
            !Prefs.setupOffered(this)
        ) {
            Prefs.setSetupOffered(this, true)
            startActivity(Intent(this, SetupActivity::class.java))
        }
        switchEnabled.isChecked = Prefs.isEnabled(this)
        modeGroup.check(
            when (Prefs.mode(this)) {
                "absolute" -> R.id.rbAbsolute
                "random" -> R.id.rbRandom
                else -> R.id.rbPercent
            }
        )
        appScopeGroup.check(
            if (Prefs.appScope(this) == AppScope.SELECTED) R.id.rbOnlySelected else R.id.rbAllApps
        )

        // ---- wire listeners ----
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            Prefs.setEnabled(this, checked)
            startServiceIfReady()
            render()
        }
        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            Prefs.setMode(
                this,
                when (checkedId) {
                    R.id.rbAbsolute -> "absolute"
                    R.id.rbRandom -> "random"
                    else -> "relative"
                }
            )
            render()
        }
        appScopeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val scope = if (checkedId == R.id.rbOnlySelected) AppScope.SELECTED else AppScope.ALL
            Prefs.setAppScope(this, scope)
            // Re-evaluate the session the service is attached to right now, so
            // switching the filter takes effect without a restart.
            startServiceIfReady()
            render()
            // The list is a separate screen: selecting "Only these" takes the
            // user straight to it, and the toggle stays on the main screen.
            if (scope == AppScope.SELECTED) openApps()
        }
        wireInput(inputAbsolute, 0, 7200) { Prefs.setAbsoluteSeconds(this, it) }
        wireInput(inputPercent, 1, 99) { Prefs.setRelativePercent(this, it) }
        wireInput(inputRandomMin, 1, 99) { Prefs.setRandomMin(this, it) }
        wireInput(inputRandomMax, 1, 99) { Prefs.setRandomMax(this, it) }

        btnManageApps.setOnClickListener { openApps() }
        cardAdvanced.setOnClickListener { openAdvanced() }

        // Registered for the whole activity lifetime (not just while resumed):
        // opening the notification shade pauses the activity, and we still
        // want the UI to refresh when its buttons are tapped.
        val prefsFilter = IntentFilter(MidtrackActionReceiver.ACTION_PREFS_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(configReceiver, prefsFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(configReceiver, prefsFilter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(configReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        startServiceIfReady()
        render()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Permissions.REQUEST_POST_NOTIFICATIONS) {
            renderHealth()
        }
    }

    /**
     * Saves on every keystroke (phones rarely fire focus-loss — the user just
     * closes the keyboard or leaves the app) and again on focus loss.
     */
    private fun wireInput(input: EditText, lo: Int, hi: Int, save: (Int) -> Unit) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toIntOrNull()
                if (v != null) {
                    save(v.coerceIn(lo, hi))
                    updatePreview()
                }
            }
        })
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val v = input.text.toString().toIntOrNull()
                if (v != null) save(v.coerceIn(lo, hi))
                render()
            }
        }
    }

    private fun render() {
        val enabled = Prefs.isEnabled(this)
        switchEnabled.isChecked = enabled

        // Keep the segmented control in sync with prefs — the mode can change
        // from the notification buttons while the app is open. Guarded: if it
        // already matches, don't call check(), otherwise its checked-change
        // listener would re-enter render().
        val modeId = when (Prefs.mode(this)) {
            "absolute" -> R.id.rbAbsolute
            "random" -> R.id.rbRandom
            else -> R.id.rbPercent
        }
        if (modeGroup.checkedButtonId != modeId) modeGroup.check(modeId)

        val mode = Prefs.mode(this)
        rowAbsolute.visibility = if (mode == "absolute") View.VISIBLE else View.GONE
        rowPercent.visibility = if (mode == "relative") View.VISIBLE else View.GONE
        rowRandom.visibility = if (mode == "random") View.VISIBLE else View.GONE

        inputAbsolute.setText(getString(R.string.number_value, Prefs.absoluteSeconds(this)))
        inputPercent.setText(getString(R.string.number_value, Prefs.relativePercent(this)))
        inputRandomMin.setText(getString(R.string.number_value, Prefs.randomMin(this)))
        inputRandomMax.setText(getString(R.string.number_value, Prefs.randomMax(this)))

        updatePreview()
        renderStatus()
        renderHealth()
        renderAppScope()
    }

    private fun renderStatus() {
        val hasAccess = Permissions.hasNotificationAccess(this)
        val enabled = Prefs.isEnabled(this)

        val onlySelected = Prefs.appScope(this) == AppScope.SELECTED
        val scopeText = if (onlySelected) selectedScopeText() else null
        val noApps = onlySelected && scopeText == null

        status.text = when {
            !hasAccess -> getString(R.string.status_enable_access)
            !enabled -> getString(R.string.status_paused)
            noApps -> getString(R.string.status_no_apps)
            scopeText != null -> getString(R.string.status_watching, scopeText)
            else -> getString(R.string.status_listening)
        }
        statusIcon.setImageResource(
            when {
                !hasAccess -> R.drawable.ic_warning
                !enabled -> R.drawable.ic_pause
                noApps -> R.drawable.ic_warning
                else -> R.drawable.ic_check
            }
        )
        statusIcon.setColorFilter(
            getColor(
                when {
                    !hasAccess -> R.color.status_warning
                    !enabled -> R.color.status_off
                    noApps -> R.color.status_warning
                    else -> R.color.status_on
                }
            )
        )
    }

    /**
     * One card for the things that silently break the feature: unfinished
     * setup, missing notification access, notifications turned off, or Android
     * battery optimization. The first issue wins; fixing it reveals the next.
     */
    private fun renderHealth() {
        val setupDone = Prefs.setupCompleted(this)
        val access = Permissions.hasNotificationAccess(this)
        val notifications = Permissions.notificationsEnabled(this)
        val battery = DeviceSettings.isIgnoringBatteryOptimizations(this)

        when {
            !setupDone -> showHealth(
                R.string.health_setup_title,
                R.string.health_setup_message,
                R.string.health_open_setup,
            ) { openSetup() }

            !access -> showHealth(
                R.string.health_access_title,
                R.string.health_access_message,
                R.string.health_enable_access,
            ) { Permissions.showNotificationAccessDisclosure(this) }

            !notifications -> {
                val needsRequest = Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED &&
                    !Prefs.notificationsRequested(this)
                showHealth(
                    R.string.health_notifications_title,
                    R.string.health_notifications_message,
                    if (needsRequest) {
                        R.string.health_allow_notifications
                    } else {
                        R.string.health_notification_settings
                    },
                ) {
                    if (needsRequest) {
                        Prefs.setNotificationsRequested(this, true)
                        Permissions.requestPostNotifications(this)
                    } else {
                        startActivity(Permissions.appNotificationSettingsIntent(this))
                    }
                }
            }

            !battery -> showHealth(
                R.string.health_battery_title,
                R.string.health_battery_message,
                R.string.health_battery_settings,
            ) { DeviceSettings.openBatterySettings(this) }

            else -> healthCard.visibility = View.GONE
        }
    }

    private fun showHealth(title: Int, message: Int, action: Int, onClick: () -> Unit) {
        healthCard.visibility = View.VISIBLE
        healthTitle.setText(title)
        healthMessage.setText(message)
        btnHealth.setText(action)
        btnHealth.setOnClickListener { onClick() }
    }

    /** Scope toggle, the selected-app summary and the link to the app list. */
    private fun renderAppScope() {
        val onlySelected = Prefs.appScope(this) == AppScope.SELECTED
        val groupId = if (onlySelected) R.id.rbOnlySelected else R.id.rbAllApps
        if (appScopeGroup.checkedButtonId != groupId) appScopeGroup.check(groupId)

        scopeSummary.setText(
            if (onlySelected) R.string.apps_scope_selected else R.string.apps_scope_all
        )

        // "All apps" shows no list at all. "Only these" shows the selected
        // subset as a compact summary; the full picker is still AppsActivity.
        val selected = if (onlySelected) {
            Prefs.selectedApps(this).sortedBy { (appLabel(it) ?: it).lowercase() }
        } else {
            emptyList()
        }
        selectedAppsList.removeAllViews()
        selectedAppsLabel.visibility =
            if (selected.isNotEmpty()) View.VISIBLE else View.GONE
        selectedAppsList.visibility =
            if (selected.isNotEmpty()) View.VISIBLE else View.GONE
        for (pkg in selected) selectedAppsList.addView(selectedAppRow(pkg))

        appListHint.visibility =
            if (onlySelected && selected.isEmpty()) View.VISIBLE else View.GONE
        btnManageApps.text = if (onlySelected) {
            getString(R.string.choose_apps)
        } else {
            getString(R.string.manage_app_rules)
        }
    }

    private fun selectedAppRow(pkg: String): View {
        val row = layoutInflater.inflate(R.layout.item_selected_app, selectedAppsList, false)
        val icon = row.findViewById<ImageView>(R.id.selectedAppIcon)
        val drawable = try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            null
        }
        if (drawable != null) icon.setImageDrawable(drawable) else icon.setImageResource(R.drawable.ic_brand)
        row.findViewById<TextView>(R.id.selectedAppName).text = appLabel(pkg) ?: pkg
        row.setOnClickListener { openApps() }
        return row
    }

    /** "YouTube Music", "YouTube Music & Spotify", "4 apps" — null when none picked. */
    private fun selectedScopeText(): String? {
        val names = selectedAppNames()
        return when {
            names.isEmpty() -> null
            names.size == 1 -> names[0]
            names.size == 2 -> getString(R.string.selected_two_apps, names[0], names[1])
            else -> getString(R.string.selected_many_apps, names.size)
        }
    }

    private fun selectedAppNames(): List<String> =
        Prefs.selectedApps(this).map { appLabel(it) ?: it }.sorted()

    private fun appLabel(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        null
    }

    @Suppress("SetTextI18n")
    private fun updatePreview() {
        // What the next new song will do: the rule of whatever is playing (it
        // may have one of its own), or the global default when nothing is.
        val activePkg = Prefs.activePkg(this)
        val cfg = Prefs.config(this, activePkg)
        val base = when (cfg.mode) {
            "absolute" -> getString(
                R.string.preview_absolute,
                SeekLogic.formatMs(cfg.absoluteSeconds * 1000L),
            )
            "random" -> {
                val lo = min(cfg.randomMin, cfg.randomMax)
                val hi = max(cfg.randomMin, cfg.randomMax)
                getString(R.string.preview_random, lo, hi)
            }
            else -> getString(R.string.preview_relative, cfg.relativePercent)
        }
        val whose = if (activePkg != null && Prefs.hasAppRule(this, activePkg)) {
            getString(R.string.preview_app_prefix, Notifications.appLabel(this, activePkg))
        } else {
            ""
        }
        preview.text = "$whose$base${SeekLogic.guardrailLabel(cfg)}"

        lastJump.text = Prefs.lastJumpTitle(this)?.let {
            getString(
                R.string.last_jump_format,
                it,
                SeekLogic.formatMs(Prefs.lastJumpAtMs(this)),
            )
        } ?: getString(R.string.no_jumps)

        refreshNotification()
    }

    /**
     * Keeps the notification in sync with the settings: reposts it with the
     * current state whenever anything changes, but only if it's actually
     * showing (i.e. the service is running).
     */
    private fun refreshNotification() = Notifications.repost(this)

    private fun startServiceIfReady() {
        if (Permissions.hasNotificationAccess(this)) {
            try {
                startForegroundService(Intent(this, MidtrackService::class.java))
            } catch (e: Exception) {
                // service already running or not ready — ignore
            }
        }
    }

    private fun openApps() {
        startActivity(Intent(this, AppsActivity::class.java))
    }

    private fun openAdvanced() {
        startActivity(Intent(this, AdvancedActivity::class.java))
    }

    private fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // no browser installed — the links are also in the README
        }
    }
}
