package com.xaepudin.midtrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-app rules: what one app's stored rule does when it is complete, when it
 * is half-written, and when it is nonsense. A stored rule must never be able
 * to break a jump, so most of this is about falling back safely.
 */
class AppRulesTest {

    private val global = SeekLogic.Config(
        enabled = true,
        mode = "relative",
        absoluteSeconds = 60,
        relativePercent = 20,
        randomMin = 15,
        randomMax = 60,
        seekOnAutoNext = true,
        minDurationSec = 90,
        maxTargetPercent = 80,
    )

    private fun decoded(raw: String) = AppRules.decode(raw, global)

    @Test
    fun `a full rule survives a round trip`() {
        val custom = global.copy(
            mode = "absolute",
            absoluteSeconds = 15,
            relativePercent = 35,
            randomMin = 10,
            randomMax = 70,
            seekOnAutoNext = false,
            minDurationSec = 0,
            maxTargetPercent = 55,
        )
        assertEquals(custom, decoded(AppRules.encode(custom)))
    }

    @Test
    fun `a rule only overrides what it stores`() {
        val rule = decoded("v1;pct=35")
        assertEquals(35, rule.relativePercent)
        // Everything else keeps the global default, including its guardrails.
        assertEquals(global.mode, rule.mode)
        assertEquals(global.minDurationSec, rule.minDurationSec)
        assertEquals(global.maxTargetPercent, rule.maxTargetPercent)
    }

    @Test
    fun `nonsense falls back to the default instead of breaking the jump`() {
        assertEquals(global, decoded(""))
        assertEquals(global, decoded("   "))
        assertEquals(global, decoded("!!!"))
        assertEquals(global, decoded("v9;something=else"))
        assertEquals(global, decoded("v1;mode=sideways;pct=abc;abs=;;"))
    }

    @Test
    fun `out of range values are clamped, not trusted`() {
        assertEquals(99, decoded("v1;pct=900").relativePercent)
        assertEquals(1, decoded("v1;rmin=0").randomMin)
        assertEquals(100, decoded("v1;maxPct=500").maxTargetPercent)
        assertEquals(0, decoded("v1;minDur=-5").minDurationSec)
        assertEquals(7200, decoded("v1;abs=999999").absoluteSeconds)
    }

    @Test
    fun `keys from a newer version are ignored`() {
        val rule = decoded("v1;pct=25;futureKnob=yes;anotherThing=7")
        assertEquals(25, rule.relativePercent)
        assertEquals(global.mode, rule.mode)
    }

    @Test
    fun `enabled always follows the master switch`() {
        // A rule says where to jump, never whether the app is on at all.
        assertTrue(decoded("v1;enabled=false").enabled)
        val off = global.copy(enabled = false)
        assertEquals(false, AppRules.decode("v1;pct=25", off).enabled)
    }

    @Test
    fun `the guardrails master switch is never stored per app`() {
        val on = global.copy(guardrailsEnabled = true)
        assertTrue(AppRules.decode("v1;minDur=0;maxPct=0", on).guardrailsEnabled)
        val off = global.copy(guardrailsEnabled = false)
        assertFalse(AppRules.decode("v1;minDur=90;maxPct=80", off).guardrailsEnabled)
    }

    @Test
    fun `effective prefers the app rule and falls back to the default`() {
        val custom = global.copy(relativePercent = 5)
        assertEquals(custom, AppRules.effective(global, custom))
        assertEquals(global, AppRules.effective(global, null))
    }

    @Test
    fun `the stored format is a stable, readable envelope`() {
        val encoded = AppRules.encode(global)
        assertTrue("unexpected format: $encoded", encoded.startsWith("v1;"))
        assertTrue(encoded.contains("mode=relative"))
        assertTrue(encoded.contains("pct=20"))
        assertNull(encoded.firstOrNull { it == '\n' })
    }
}
