package com.xaepudin.midtrack

import kotlin.math.max
import kotlin.math.min

/**
 * Target computation: absolute seconds, percent of the song, or a random
 * percent window. Never lands on the very end of the song.
 *
 * Two optional guardrails stop a jump from ruining a song (both off when 0):
 *  - [Config.minDurationSec]    don't jump in anything shorter than this;
 *  - [Config.maxTargetPercent]  never jump past this fraction of the song.
 * The master switch [Config.guardrailsEnabled] is off by default.
 * Plus one deliberate non-feature: a track whose length is unknown is never
 * seeked. Live streams, ads and apps that don't post METADATA_KEY_DURATION
 * have no "safe spot" — without a length we cannot stay away from the end.
 */
object SeekLogic {

    data class Config(
        val enabled: Boolean,
        val mode: String, // "absolute" | "relative" | "random"
        val absoluteSeconds: Int,
        val relativePercent: Int,
        val randomMin: Int,
        val randomMax: Int,
        val seekOnAutoNext: Boolean,
        /** Skip tracks shorter than this many seconds (0 = no minimum). */
        val minDurationSec: Int = 0,
        /** Never jump past this percent of the track (0 = no cap). */
        val maxTargetPercent: Int = 0,
        /** Master switch for both guardrails. Off by default. */
        val guardrailsEnabled: Boolean = false,
    )

    fun computeTarget(durationMs: Long, cfg: Config): Long? {
        if (skipReason(durationMs, cfg) != null) return null
        val dur = durationMs / 1000.0
        val t: Double = when (cfg.mode) {
            "absolute" -> cfg.absoluteSeconds.toDouble()
            "random" -> {
                val lo = min(cfg.randomMin, cfg.randomMax).toDouble()
                val hi = max(cfg.randomMin, cfg.randomMax).toDouble()
                dur * (lo + Math.random() * (hi - lo)) / 100.0
            }
            else -> dur * cfg.relativePercent / 100.0
        }
        if (!t.isFinite() || t <= 0.0) return null
        // Never land on the very end, and never past the user's cap.
        var ceiling = dur - 2.0
        if (cfg.guardrailsEnabled && cfg.maxTargetPercent in 1..99) {
            ceiling = min(ceiling, dur * cfg.maxTargetPercent / 100.0)
        }
        val maxTarget = max(ceiling, 1.0)
        val clamped = min(max(t, 0.5), maxTarget)
        return (clamped * 1000.0).toLong()
    }

    /**
     * Why this track gets no jump at all, or null when it is a candidate.
     * The service logs it, so "nothing happened" is always explainable.
     */
    fun skipReason(durationMs: Long, cfg: Config): String? {
        val dur = durationMs / 1000.0
        if (dur <= 1.0) return "track length unknown (live stream, ad, or no duration metadata)"
        if (cfg.guardrailsEnabled && cfg.minDurationSec > 0 && dur < cfg.minDurationSec) {
            return "track is ${formatMs(durationMs)} — shorter than the " +
                "${formatMs(cfg.minDurationSec * 1000L)} minimum"
        }
        return null
    }

    /** Natural end detection: was the previous track within the last 3 s? */
    fun isAutoNext(lastPositionMs: Long, lastDurationMs: Long): Boolean =
        lastDurationMs > 0 && lastPositionMs >= lastDurationMs - 3000

    /** absolute → relative → random → absolute (the notification's Mode button). */
    fun nextMode(mode: String): String = when (mode) {
        "absolute" -> "relative"
        "relative" -> "random"
        else -> "absolute"
    }

    /** "1:00", "20%", "random 15–60%" — one label for every surface. */
    fun modeLabel(cfg: Config): String = when (cfg.mode) {
        "absolute" -> formatMs(cfg.absoluteSeconds * 1000L)
        "random" -> {
            val lo = min(cfg.randomMin, cfg.randomMax)
            val hi = max(cfg.randomMin, cfg.randomMax)
            "random $lo–$hi%"
        }
        else -> "${cfg.relativePercent}%"
    }

    /** " · >1:30, ≤80%" — the guardrails in force, or "" when disabled. */
    fun guardrailLabel(cfg: Config): String {
        if (!cfg.guardrailsEnabled) return ""
        val parts = ArrayList<String>(2)
        if (cfg.minDurationSec > 0) parts += ">${formatMs(cfg.minDurationSec * 1000L)}"
        if (cfg.maxTargetPercent in 1..99) parts += "≤${cfg.maxTargetPercent}%"
        return if (parts.isEmpty()) "" else parts.joinToString(", ", prefix = " · ")
    }

    fun formatMs(ms: Long): String {
        val total = ms / 1000
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }
}
