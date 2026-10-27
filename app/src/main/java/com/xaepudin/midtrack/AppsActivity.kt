package com.xaepudin.midtrack

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.Executors

/**
 * The app list and per-app rules. Kept off the main screen. The list loads on
 * a background thread, uses a RecyclerView for large package counts, and only
 * shows real players by default; "Show all apps" reveals generic audio
 * handlers and the rest of the launcher.
 */
class AppsActivity : Activity(), AppListAdapter.Callbacks {

    private lateinit var appList: RecyclerView
    private lateinit var appListHint: TextView
    private lateinit var appListMore: TextView
    private lateinit var switchShowAll: MaterialSwitch
    private lateinit var inputAppSearch: EditText
    private lateinit var scopeSummary: TextView
    private lateinit var selectionActions: View
    private lateinit var loading: ProgressBar
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var btnClearAll: MaterialButton

    private lateinit var adapter: AppListAdapter

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var loadGeneration = 0
    private var loadingInFlight = false
    private var allEntries: List<AppEntry> = emptyList()
    private var pendingListState: Parcelable? = null

    private val searchRunnable = Runnable { reload(resetScroll = true) }

    /** Apps holding a media session right now, and the subset that is playing. */
    private var sessionPackages: Set<String> = emptySet()
    private var playingPackages: Set<String> = emptySet()

    // PackageManager queries are slowish and only change on install/uninstall.
    private var musicAppsCache: Set<String>? = null
    private var mediaBrowserCache: Set<String>? = null
    private var audioHandlersCache: Set<String>? = null
    private var launcherAppsCache: Set<String>? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_apps)

        val root = findViewById<View>(R.id.appsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.apps_title)
        toolbar.navigationContentDescription = getString(R.string.back)
        toolbar.setNavigationOnClickListener { finish() }

        appList = findViewById(R.id.appList)
        appListHint = findViewById(R.id.appListHint)
        appListMore = findViewById(R.id.appListMore)
        switchShowAll = findViewById(R.id.switchShowAll)
        inputAppSearch = findViewById(R.id.inputAppSearch)
        scopeSummary = findViewById(R.id.scopeSummary)
        selectionActions = findViewById(R.id.selectionActions)
        loading = findViewById(R.id.loading)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnClearAll = findViewById(R.id.btnClearAll)

        adapter = AppListAdapter(this, ::appStateText, this)
        adapter.scopeSelected = Prefs.appScope(this) == AppScope.SELECTED
        adapter.selectedPackages = Prefs.selectedApps(this)
        adapter.expandedPkg = savedInstanceState?.getString(KEY_EXPANDED)
        pendingListState = savedInstanceState?.getParcelable(KEY_LIST_STATE)
        appList.adapter = adapter

        savedInstanceState?.getString(KEY_QUERY)?.let { inputAppSearch.setText(it) }
        switchShowAll.isChecked = Prefs.showAllApps(this)

        switchShowAll.setOnCheckedChangeListener { _, checked ->
            if (Prefs.showAllApps(this) == checked) return@setOnCheckedChangeListener
            Prefs.setShowAllApps(this, checked)
            reload(resetScroll = true)
        }
        inputAppSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(searchRunnable)
                handler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS)
            }
        })
        btnSelectAll.setOnClickListener { selectAllPlayers() }
        btnClearAll.setOnClickListener { clearAll() }

        renderChrome()
        reload(resetScroll = savedInstanceState == null)
    }

    override fun onResume() {
        super.onResume()
        renderChrome()
        if (!loadingInFlight && allEntries.isEmpty()) reload(resetScroll = false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_QUERY, inputAppSearch.text?.toString().orEmpty())
        outState.putString(KEY_EXPANDED, adapter.expandedPkg)
        outState.putParcelable(KEY_LIST_STATE, appList.layoutManager?.onSaveInstanceState())
    }

    /** Summary, bulk actions and switch states — cheap, safe to call often. */
    private fun renderChrome() {
        val onlySelected = Prefs.appScope(this) == AppScope.SELECTED
        scopeSummary.setText(
            if (onlySelected) R.string.apps_scope_selected else R.string.apps_scope_all
        )
        selectionActions.visibility = if (onlySelected) View.VISIBLE else View.GONE
        appListHint.visibility =
            if (onlySelected && Prefs.selectedApps(this).isEmpty()) View.VISIBLE else View.GONE
        if (switchShowAll.isChecked != Prefs.showAllApps(this)) {
            switchShowAll.isChecked = Prefs.showAllApps(this)
        }
        adapter.scopeSelected = onlySelected
        adapter.selectedPackages = Prefs.selectedApps(this)
    }

    /**
     * Load the package list off the main thread. A generation counter keeps a
     * late result from an older query out of the adapter.
     */
    private fun reload(resetScroll: Boolean) {
        if (resetScroll) pendingListState = null
        val generation = ++loadGeneration
        loadingInFlight = true
        loading.visibility = View.VISIBLE
        val query = inputAppSearch.text?.toString()?.trim().orEmpty()

        executor.execute {
            var failed = false
            val entries = try {
                appEntries(query)
            } catch (e: Exception) {
                failed = true
                emptyList()
            }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                if (generation != loadGeneration) return@post
                loadingInFlight = false
                loading.visibility = View.GONE
                allEntries = entries
                adapter.scopeSelected = Prefs.appScope(this) == AppScope.SELECTED
                adapter.selectedPackages = Prefs.selectedApps(this)
                adapter.submit(entries.take(MAX_APP_ROWS))
                updateListMore(entries, failed)
                val state = pendingListState
                if (state != null) {
                    appList.layoutManager?.onRestoreInstanceState(state)
                    pendingListState = null
                } else if (resetScroll) {
                    appList.scrollToPosition(0)
                }
            }
        }
    }

    private fun updateListMore(entries: List<AppEntry>, failed: Boolean) {
        val shown = minOf(entries.size, MAX_APP_ROWS)
        when {
            failed -> {
                appListMore.visibility = View.VISIBLE
                appListMore.setText(R.string.apps_load_failed)
            }
            entries.isEmpty() -> {
                appListMore.visibility = View.VISIBLE
                appListMore.setText(R.string.apps_empty)
            }
            entries.size > shown -> {
                appListMore.visibility = View.VISIBLE
                appListMore.text = getString(R.string.apps_showing, shown, entries.size)
            }
            else -> appListMore.visibility = View.GONE
        }
    }

    /**
     * Real players: live sessions, selected apps, declared music apps, apps
     * seen before, and pinned apps. Media-browser/audio-handler/launcher apps
     * only join the list behind "Show all apps".
     */
    private fun appEntries(query: String): List<AppEntry> {
        refreshSessions()
        val selected = Prefs.selectedApps(this)
        val pinned = Prefs.pinnedApps(this)
        val seen = Prefs.seenApps(this)
        val declaredMusic = declaredMusicAppPackages()
        val packages = LinkedHashSet<String>()
        packages += sessionPackages
        packages += selected
        packages += pinned
        packages += declaredMusic
        packages += seen
        if (Prefs.showAllApps(this)) {
            packages += mediaBrowserPackages()
            packages += audioHandlerPackages()
            packages += launcherAppPackages()
        }

        val needle = query.lowercase()
        val entries = ArrayList<AppEntry>(packages.size)
        for (pkg in packages) {
            if (pkg == packageName) continue
            val label = appLabel(pkg)
            // An uninstalled leftover is only worth showing while it still
            // explains something (selected, pinned, or holding a live session).
            if (label == null && pkg !in selected && pkg !in pinned && pkg !in sessionPackages) continue
            val name = label ?: pkg
            if (needle.isNotEmpty() &&
                !name.lowercase().contains(needle) && !pkg.lowercase().contains(needle)
            ) continue
            val isPlayer = pkg in declaredMusic || pkg in seen ||
                pkg in selected || pkg in sessionPackages
            val isPinned = pkg in pinned
            val rank = when {
                pkg in playingPackages -> 0
                isPinned -> 1
                isPlayer -> 2
                else -> 3
            }
            entries += AppEntry(
                pkg = pkg,
                label = name,
                playing = pkg in playingPackages,
                pinned = isPinned,
                rank = rank,
                icon = appIcon(pkg),
            )
        }
        entries.sortWith(compareBy<AppEntry> { it.rank }.thenBy { it.label.lowercase() })
        return entries
    }

    private fun appStateText(entry: AppEntry): String {
        val custom = Prefs.hasAppRule(this, entry.pkg)
        val cfg = Prefs.config(this, entry.pkg)
        return buildString {
            append(
                getString(
                    if (custom) R.string.apps_own_rule_state else R.string.apps_default_state,
                    SeekLogic.modeLabel(cfg),
                )
            )
            if (entry.playing) append(" · ").append(getString(R.string.apps_playing_now))
            if (Prefs.appScope(this@AppsActivity) == AppScope.SELECTED &&
                entry.pkg !in Prefs.selectedApps(this@AppsActivity)
            ) {
                append(" · ").append(getString(R.string.apps_not_seeked))
            }
            append(" · ").append(entry.pkg)
        }
    }

    override fun onToggle(entry: AppEntry, selected: Boolean) {
        Prefs.setAppSelected(this, entry.pkg, selected)
        adapter.selectedPackages = Prefs.selectedApps(this)
        startServiceIfReady()
        adapter.refreshState(entry.pkg)
        appListHint.visibility =
            if (Prefs.appScope(this) == AppScope.SELECTED && Prefs.selectedApps(this).isEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        sendPrefsChanged()
    }

    override fun onRule(entry: AppEntry) {
        adapter.toggleExpanded(entry.pkg)
    }

    override fun onPin(entry: AppEntry) {
        val pinned = !entry.pinned
        Prefs.setAppPinned(this, entry.pkg, pinned)
        Toast.makeText(
            this,
            getString(
                if (pinned) R.string.apps_pinned_toast else R.string.apps_unpinned_toast,
                entry.label,
            ),
            Toast.LENGTH_SHORT,
        ).show()
        reload(resetScroll = false)
    }

    override fun onRuleChanged(pkg: String) {
        sendPrefsChanged()
    }

    override fun onRuleReset(pkg: String) {
        sendPrefsChanged()
    }

    private fun selectAllPlayers() {
        val candidates = allEntries.filter { it.rank <= 2 }.map { it.pkg }.toSet()
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.apps_no_players, Toast.LENGTH_SHORT).show()
            return
        }
        val next = Prefs.selectedApps(this) + candidates
        Prefs.replaceSelectedApps(this, next)
        adapter.selectedPackages = next
        adapter.notifyDataSetChanged()
        appListHint.visibility = View.GONE
        startServiceIfReady()
        Toast.makeText(
            this,
            getString(R.string.apps_selected_count, next.size),
            Toast.LENGTH_SHORT,
        ).show()
        sendPrefsChanged()
    }

    private fun clearAll() {
        Prefs.replaceSelectedApps(this, emptySet())
        adapter.selectedPackages = emptySet()
        adapter.notifyDataSetChanged()
        appListHint.visibility = View.VISIBLE
        startServiceIfReady()
        Toast.makeText(this, R.string.apps_cleared, Toast.LENGTH_SHORT).show()
        sendPrefsChanged()
    }

    private fun sendPrefsChanged() {
        sendBroadcast(Intent(MidtrackActionReceiver.ACTION_PREFS_CHANGED).setPackage(packageName))
    }

    /** Apps with a live media session right now (needs notification access). */
    private fun refreshSessions() {
        try {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = ComponentName(this, MidtrackService::class.java)
            val sessions = msm.getActiveSessions(comp).orEmpty()
                .filter { it.packageName != packageName }
            sessionPackages = sessions.map { it.packageName }.toSet()
            playingPackages = sessions
                .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                .map { it.packageName }
                .toSet()
            sessionPackages.forEach { Prefs.rememberSeenApp(this, it) }
        } catch (e: Exception) {
            sessionPackages = emptySet()
            playingPackages = emptySet()
        }
    }

    /** Apps that declare themselves the music app (YouTube Music, Musicolet, …). */
    private fun declaredMusicAppPackages(): Set<String> {
        musicAppsCache?.let { return it }
        val packages = LinkedHashSet<String>()
        try {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0
            ).forEach { packages += it.activityInfo.packageName }
        } catch (e: Exception) {
            // visibility restrictions — the other queries still apply
        }
        packages.remove(packageName)
        musicAppsCache = packages
        return packages
    }

    /**
     * Apps exposing a MediaBrowserService (Spotify, Deezer, car/auto players).
     * Plenty of non-players have one too, so these only appear when "Show all
     * apps" is on.
     */
    private fun mediaBrowserPackages(): Set<String> {
        mediaBrowserCache?.let { return it }
        val packages = LinkedHashSet<String>()
        try {
            packageManager.queryIntentServices(Intent(MEDIA_BROWSER_SERVICE), 0)
                .forEach { packages += it.serviceInfo.packageName }
        } catch (e: Exception) {
            // nothing to add
        }
        packages.remove(packageName)
        mediaBrowserCache = packages
        return packages
    }

    /**
     * Apps that merely handle audio files — file managers, Bluetooth, voice
     * recorders. Legitimate targets, but not players; Show all only.
     */
    private fun audioHandlerPackages(): Set<String> {
        audioHandlersCache?.let { return it }
        val packages = LinkedHashSet<String>()
        try {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse("content://media/external/audio/media"), "audio/*"), 0
            ).forEach { packages += it.activityInfo.packageName }
        } catch (e: Exception) {
            // nothing to add
        }
        packages.remove(packageName)
        audioHandlersCache = packages
        return packages
    }

    /** Everything with a launcher icon — behind the "Show all apps" switch. */
    private fun launcherAppPackages(): Set<String> {
        launcherAppsCache?.let { return it }
        val packages = LinkedHashSet<String>()
        try {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).forEach { packages += it.activityInfo.packageName }
        } catch (e: Exception) {
            // nothing more we can offer
        }
        packages.remove(packageName)
        launcherAppsCache = packages
        return packages
    }

    private fun appLabel(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        null
    }

    private fun appIcon(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) {
        null
    }

    private fun startServiceIfReady() {
        if (Permissions.hasNotificationAccess(this)) {
            try {
                startForegroundService(Intent(this, MidtrackService::class.java))
            } catch (e: Exception) {
                // service already running or not ready — ignore
            }
        }
    }

    private companion object {
        const val MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService"
        const val MAX_APP_ROWS = 60
        const val SEARCH_DEBOUNCE_MS = 200L
        const val KEY_QUERY = "query"
        const val KEY_EXPANDED = "expanded"
        const val KEY_LIST_STATE = "listState"
    }
}
