package com.xaepudin.midtrack

/**
 * Which apps Midtrack is allowed to seek in.
 *
 * "all"      — every app that plays media (the original behaviour).
 * "selected" — only the whitelisted packages (e.g. YouTube Music, but not YouTube).
 *
 * Pure logic, no Android dependencies, so the rule stays in one place and is
 * easy to reason about: an app with no package name is never seeked, and an
 * unknown/absent mode falls back to "all" (pre-existing installs keep working).
 */
object AppScope {
    const val ALL = "all"
    const val SELECTED = "selected"

    fun allows(mode: String, selected: Set<String>, pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return when (mode) {
            SELECTED -> pkg in selected
            else -> true
        }
    }
}
