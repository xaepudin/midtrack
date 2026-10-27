package com.xaepudin.midtrack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whitelist rule is the one piece of this feature that can be checked
 * without a device: which packages Midtrack is allowed to seek in.
 */
class AppScopeTest {

    private val youtubeMusic = "com.google.android.apps.youtube.music"
    private val youTube = "com.google.android.youtube"

    @Test
    fun `all mode allows any player`() {
        assertTrue(AppScope.allows(AppScope.ALL, emptySet(), youtubeMusic))
        assertTrue(AppScope.allows(AppScope.ALL, emptySet(), youTube))
    }

    @Test
    fun `selected mode allows only the whitelisted packages`() {
        val selected = setOf(youtubeMusic)
        assertTrue(AppScope.allows(AppScope.SELECTED, selected, youtubeMusic))
        assertFalse(AppScope.allows(AppScope.SELECTED, selected, youTube))
    }

    @Test
    fun `selected mode with nothing picked allows nothing`() {
        assertFalse(AppScope.allows(AppScope.SELECTED, emptySet(), youtubeMusic))
        assertFalse(AppScope.allows(AppScope.SELECTED, emptySet(), youTube))
    }

    @Test
    fun `a missing package is never seeked`() {
        assertFalse(AppScope.allows(AppScope.ALL, emptySet(), null))
        assertFalse(AppScope.allows(AppScope.SELECTED, setOf(youtubeMusic), null))
        assertFalse(AppScope.allows(AppScope.ALL, emptySet(), ""))
    }

    @Test
    fun `an unknown mode falls back to all apps`() {
        // Pre-existing installs have no stored mode — they must keep working.
        assertTrue(AppScope.allows("", emptySet(), youTube))
        assertTrue(AppScope.allows("something-else", emptySet(), youTube))
    }
}
