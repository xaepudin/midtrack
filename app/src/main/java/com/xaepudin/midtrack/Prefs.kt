package com.xaepudin.midtrack

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings storage.
 *
 * Two layers, on purpose:
 *  - the **global default** (`config`), edited in the main settings cards;
 *  - optional **per-app rules** ([config] with a package), which override the
 *    default for one player only. Whether an app is seeked at all stays with
 *    the master switch and the whitelist — a rule only says *where* to jump.
 */
object Prefs {
    private const val FILE = "settings"
    private const val RULE_PREFIX = "appRule."
    private const val KEY_DEFAULTS_VERSION = "defaultsVersion"
    private const val DEFAULTS_VERSION = 2

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * One-time default changes. Version 1 shipped with "Always show
     * notification" on; version 2 turns it off. Runs before the UI reads prefs.
     */
    fun migrateDefaults(ctx: Context) {
        val sp = sp(ctx)
        if (sp.getInt(KEY_DEFAULTS_VERSION, 1) < DEFAULTS_VERSION) {
            sp.edit()
                .putBoolean("repinEnabled", false)
                .putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION)
                .apply()
        }
    }

    /** Stored guardrail defaults; only used while the master switch is on. */
    private const val DEFAULT_MIN_DURATION_SEC = 90
    private const val DEFAULT_MAX_TARGET_PERCENT = 80

    /** The global default every app without a rule of its own inherits. */
    fun config(ctx: Context) = SeekLogic.Config(
        enabled = isEnabled(ctx),
        mode = mode(ctx),
        absoluteSeconds = absoluteSeconds(ctx),
        relativePercent = relativePercent(ctx),
        randomMin = randomMin(ctx),
        randomMax = randomMax(ctx),
        seekOnAutoNext = seekOnAutoNext(ctx),
        minDurationSec = minDurationSec(ctx),
        maxTargetPercent = maxTargetPercent(ctx),
        guardrailsEnabled = guardrailsEnabled(ctx),
    )

    /**
     * The rule that applies to [pkg]: its own when it has one, the global
     * default otherwise. [pkg] null (nothing attached) also means the default.
     */
    fun config(ctx: Context, pkg: String?): SeekLogic.Config {
        val global = config(ctx)
        if (pkg.isNullOrEmpty()) return global
        val raw = sp(ctx).getString(ruleKey(pkg), null) ?: return global
        return AppRules.decode(raw, global)
    }

    fun isEnabled(ctx: Context) = sp(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("enabled", v).apply()

    /** The first-run checklist was finished or deliberately skipped. */
    fun setupCompleted(ctx: Context) = sp(ctx).getBoolean("setupCompleted", false)
    fun setSetupCompleted(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("setupCompleted", v).apply()

    /** The checklist was opened automatically once; do not force it again. */
    fun setupOffered(ctx: Context) = sp(ctx).getBoolean("setupOffered", false)
    fun setSetupOffered(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("setupOffered", v).apply()

    /** We already showed the Android 13 notification permission dialog. */
    fun notificationsRequested(ctx: Context) = sp(ctx).getBoolean("notificationsRequested", false)
    fun setNotificationsRequested(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("notificationsRequested", v).apply()

    fun mode(ctx: Context) = sp(ctx).getString("mode", "relative") ?: "relative"
    fun setMode(ctx: Context, v: String) = sp(ctx).edit().putString("mode", v).apply()

    fun absoluteSeconds(ctx: Context) = sp(ctx).getInt("absoluteSeconds", 60)
    fun setAbsoluteSeconds(ctx: Context, v: Int) = sp(ctx).edit().putInt("absoluteSeconds", v).apply()

    fun relativePercent(ctx: Context) = sp(ctx).getInt("relativePercent", 20)
    fun setRelativePercent(ctx: Context, v: Int) = sp(ctx).edit().putInt("relativePercent", v).apply()

    fun randomMin(ctx: Context) = sp(ctx).getInt("randomMin", 15)
    fun setRandomMin(ctx: Context, v: Int) = sp(ctx).edit().putInt("randomMin", v).apply()

    fun randomMax(ctx: Context) = sp(ctx).getInt("randomMax", 60)
    fun setRandomMax(ctx: Context, v: Int) = sp(ctx).edit().putInt("randomMax", v).apply()

    fun seekOnAutoNext(ctx: Context) = sp(ctx).getBoolean("seekOnAutoNext", true)
    fun setSeekOnAutoNext(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("seekOnAutoNext", v).apply()

    // ---- guardrails ----

    /** Master switch for both guardrails. Off by default. */
    fun guardrailsEnabled(ctx: Context) = sp(ctx).getBoolean("guardrailsEnabled", false)
    fun setGuardrailsEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("guardrailsEnabled", v).apply()

    /** Tracks shorter than this are left alone (0 = no minimum). */
    fun minDurationSec(ctx: Context) = sp(ctx).getInt("minDurationSec", DEFAULT_MIN_DURATION_SEC)
    fun setMinDurationSec(ctx: Context, v: Int) = sp(ctx).edit().putInt("minDurationSec", v).apply()

    /** Never jump past this percent of the track (0 = no cap). */
    fun maxTargetPercent(ctx: Context) = sp(ctx).getInt("maxTargetPercent", DEFAULT_MAX_TARGET_PERCENT)
    fun setMaxTargetPercent(ctx: Context, v: Int) = sp(ctx).edit().putInt("maxTargetPercent", v).apply()

    /** Keep the notification pinned when swiped. Off by default. */
    fun repinEnabled(ctx: Context) = sp(ctx).getBoolean("repinEnabled", false)
    fun setRepinEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("repinEnabled", v).apply()

    // ---- app scope (whitelist) ----

    /** [AppScope.ALL] = any app, [AppScope.SELECTED] = whitelist only. */
    fun appScope(ctx: Context) = sp(ctx).getString("appScope", AppScope.ALL) ?: AppScope.ALL
    fun setAppScope(ctx: Context, v: String) = sp(ctx).edit().putString("appScope", v).apply()

    /** Whitelisted package names — only consulted when [appScope] is "selected". */
    fun selectedApps(ctx: Context): Set<String> =
        sp(ctx).getStringSet("selectedApps", emptySet())?.toSet() ?: emptySet()

    fun setAppSelected(ctx: Context, pkg: String, selected: Boolean) {
        val next = selectedApps(ctx).toMutableSet()
        if (selected) next.add(pkg) else next.remove(pkg)
        sp(ctx).edit().putStringSet("selectedApps", next).apply()
    }

    /** Bulk edit for select-all / clear-all without one apply per app. */
    fun replaceSelectedApps(ctx: Context, apps: Set<String>) =
        sp(ctx).edit().putStringSet("selectedApps", apps).apply()

    /** Apps pinned to the top of the picker. */
    fun pinnedApps(ctx: Context): Set<String> =
        sp(ctx).getStringSet("pinnedApps", emptySet())?.toSet() ?: emptySet()

    fun setAppPinned(ctx: Context, pkg: String, pinned: Boolean) {
        val next = pinnedApps(ctx).toMutableSet()
        if (pinned) next.add(pkg) else next.remove(pkg)
        sp(ctx).edit().putStringSet("pinnedApps", next).apply()
    }

    /** Every package we have ever seen holding a media session — feeds the picker. */
    fun seenApps(ctx: Context): Set<String> =
        sp(ctx).getStringSet("seenApps", emptySet())?.toSet() ?: emptySet()

    /** Remembers a player so it can be whitelisted later (no-op when already known). */
    fun rememberSeenApp(ctx: Context, pkg: String) {
        if (pkg.isEmpty() || pkg == ctx.packageName) return
        val seen = seenApps(ctx)
        if (pkg in seen) return
        sp(ctx).edit().putStringSet("seenApps", seen + pkg).apply()
    }

    /** Picker shows every installed app, not just the detected players. */
    fun showAllApps(ctx: Context) = sp(ctx).getBoolean("showAllApps", false)
    fun setShowAllApps(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("showAllApps", v).apply()

    /** Single source of truth for "may we seek in this app right now?". */
    fun isAppAllowed(ctx: Context, pkg: String?): Boolean =
        AppScope.allows(appScope(ctx), selectedApps(ctx), pkg)

    // ---- per-app rules ----

    private fun ruleKey(pkg: String) = "$RULE_PREFIX$pkg"

    /** True when this app has a rule of its own instead of the global default. */
    fun hasAppRule(ctx: Context, pkg: String) = sp(ctx).contains(ruleKey(pkg))

    fun setAppRule(ctx: Context, pkg: String, cfg: SeekLogic.Config) =
        sp(ctx).edit().putString(ruleKey(pkg), AppRules.encode(cfg)).apply()

    /** Back to the global default for this app. */
    fun clearAppRule(ctx: Context, pkg: String) = sp(ctx).edit().remove(ruleKey(pkg)).apply()

    fun lastJumpTitle(ctx: Context) = sp(ctx).getString("lastJumpTitle", null)
    fun lastJumpAtMs(ctx: Context) = sp(ctx).getLong("lastJumpAtMs", -1L)
    fun setLastJump(ctx: Context, title: String, atMs: Long) =
        sp(ctx).edit().putString("lastJumpTitle", title).putLong("lastJumpAtMs", atMs).apply()

    // ---- what the notification shows ----

    /** Package the service is attached to right now (null when nothing is playing). */
    fun activePkg(ctx: Context) = sp(ctx).getString("activePkg", null)

    /** Title of the track the service is attached to right now. */
    fun nowTitle(ctx: Context) = sp(ctx).getString("nowTitle", null)

    fun setNowPlaying(ctx: Context, pkg: String, title: String?) =
        sp(ctx).edit().putString("activePkg", pkg).putString("nowTitle", title).apply()

    /** Nothing is playing (or we let go of the session) — drop the now-playing line. */
    fun clearNowPlaying(ctx: Context) = sp(ctx).edit().remove("activePkg").remove("nowTitle").apply()
}
