package com.xaepudin.midtrack

/**
 * Per-app rules: any app can carry its own copy of the jump settings, so
 * YouTube Music can jump 20% in, YouTube can skip straight to 0:15, and a
 * podcast player can be told never to jump past the intro.
 *
 * A rule only decides *where* to jump — whether an app is seeked at all stays
 * with the master switch and the [AppScope] whitelist, so there is exactly one
 * place to turn things off.
 *
 * Storage is a tiny dependency-free text format:
 *
 *     v1;mode=relative;pct=20;abs=60;rmin=15;rmax=60;autoNext=true;minDur=90;maxPct=80
 *
 * Deliberately not JSON: it needs no parser on the unit-test classpath, it
 * tolerates fields being added or dropped, and a future export can round-trip
 * the very same string.
 */
object AppRules {

    const val VERSION = "v1"

    private val MODES = setOf("absolute", "relative", "random")

    fun encode(cfg: SeekLogic.Config): String = buildString {
        append(VERSION)
        append(";mode=").append(cfg.mode)
        append(";abs=").append(cfg.absoluteSeconds)
        append(";pct=").append(cfg.relativePercent)
        append(";rmin=").append(cfg.randomMin)
        append(";rmax=").append(cfg.randomMax)
        append(";autoNext=").append(cfg.seekOnAutoNext)
        append(";minDur=").append(cfg.minDurationSec)
        append(";maxPct=").append(cfg.maxTargetPercent)
    }

    /**
     * Tolerant by design: anything missing, unparsable or out of range falls
     * back to [fallback] — a stored rule must never be able to break the app,
     * and a rule saved by a newer version must still load here.
     */
    fun decode(raw: String, fallback: SeekLogic.Config): SeekLogic.Config {
        if (raw.isBlank()) return fallback
        val fields = HashMap<String, String>()
        for (part in raw.split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0) fields[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
        }
        fun bool(key: String, def: Boolean) = when (fields[key]?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> def
        }
        fun int(key: String, def: Int, lo: Int, hi: Int) =
            fields[key]?.toIntOrNull()?.coerceIn(lo, hi) ?: def

        return SeekLogic.Config(
            // `enabled` and `guardrailsEnabled` are never stored per app — the
            // master switches own them; the rule only stores its own limits.
            enabled = fallback.enabled,
            mode = fields["mode"]?.takeIf { it in MODES } ?: fallback.mode,
            absoluteSeconds = int("abs", fallback.absoluteSeconds, 0, 7200),
            relativePercent = int("pct", fallback.relativePercent, 1, 99),
            randomMin = int("rmin", fallback.randomMin, 1, 99),
            randomMax = int("rmax", fallback.randomMax, 1, 99),
            seekOnAutoNext = bool("autoNext", fallback.seekOnAutoNext),
            minDurationSec = int("minDur", fallback.minDurationSec, 0, 7200),
            maxTargetPercent = int("maxPct", fallback.maxTargetPercent, 0, 100),
            guardrailsEnabled = fallback.guardrailsEnabled,
        )
    }

    /** The rule that actually applies: the app's own, or the global default. */
    fun effective(global: SeekLogic.Config, override: SeekLogic.Config?): SeekLogic.Config =
        override ?: global
}
