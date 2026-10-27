package com.xaepudin.midtrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guardrails and the target maths — the part of the app that decides
 * whether a jump happens at all, and where it lands. All pure logic, so it is
 * checked here instead of on a device with a song playing.
 */
class SeekLogicTest {

    private fun cfg(
        mode: String = "relative",
        absoluteSeconds: Int = 60,
        relativePercent: Int = 20,
        randomMin: Int = 15,
        randomMax: Int = 60,
        autoNext: Boolean = true,
        minDurationSec: Int = 0,
        maxTargetPercent: Int = 0,
        guardrailsEnabled: Boolean = true,
    ) = SeekLogic.Config(
        enabled = true,
        mode = mode,
        absoluteSeconds = absoluteSeconds,
        relativePercent = relativePercent,
        randomMin = randomMin,
        randomMax = randomMax,
        seekOnAutoNext = autoNext,
        minDurationSec = minDurationSec,
        maxTargetPercent = maxTargetPercent,
        guardrailsEnabled = guardrailsEnabled,
    )

    // ---- the feature itself ----

    @Test
    fun `percent mode lands the configured fraction in`() {
        assertEquals(60_000L, SeekLogic.computeTarget(300_000L, cfg(relativePercent = 20)))
    }

    @Test
    fun `absolute mode ignores the track length`() {
        assertEquals(60_000L, SeekLogic.computeTarget(600_000L, cfg(mode = "absolute")))
        assertEquals(60_000L, SeekLogic.computeTarget(120_000L, cfg(mode = "absolute")))
    }

    @Test
    fun `random mode stays inside its window`() {
        val cfg = cfg(mode = "random", randomMin = 15, randomMax = 60)
        repeat(200) {
            val target = SeekLogic.computeTarget(200_000L, cfg) ?: error("expected a target")
            assertTrue("$target below the window", target >= 200_000L * 15 / 100 - 1000)
            assertTrue("$target above the window", target <= 200_000L * 60 / 100 + 1000)
        }
    }

    // ---- guardrail: unknown / unusable length ----

    @Test
    fun `a track with no length is never seeked`() {
        // Live streams, ads, and players that never post METADATA_KEY_DURATION.
        assertNull(SeekLogic.computeTarget(0L, cfg()))
        assertNull(SeekLogic.computeTarget(1000L, cfg()))
        assertNull(SeekLogic.computeTarget(-1L, cfg(mode = "absolute")))
        assertEquals(
            "track length unknown (live stream, ad, or no duration metadata)",
            SeekLogic.skipReason(0L, cfg()),
        )
    }

    // ---- guardrail: master switch ----

    @Test
    fun `guardrails do nothing until the master switch is on`() {
        val off = cfg(minDurationSec = 90, maxTargetPercent = 30, guardrailsEnabled = false)
        assertNull(SeekLogic.skipReason(60_000L, off))
        assertEquals(
            60_000L,
            SeekLogic.computeTarget(120_000L, off.copy(mode = "absolute")),
        )
        assertEquals("", SeekLogic.guardrailLabel(off))
        // The unknown-length non-feature is not a guardrail and still applies.
        assertNull(SeekLogic.computeTarget(0L, off))
    }

    // ---- guardrail: minimum length ----

    @Test
    fun `short tracks are skipped when a minimum is set`() {
        val cfg = cfg(minDurationSec = 90)
        assertNull(SeekLogic.computeTarget(60_000L, cfg))
        assertNotNull(SeekLogic.computeTarget(90_000L, cfg))
        assertEquals("track is 1:00 — shorter than the 1:30 minimum", SeekLogic.skipReason(60_000L, cfg))
    }

    @Test
    fun `a minimum of zero means no minimum`() {
        assertNotNull(SeekLogic.computeTarget(30_000L, cfg(minDurationSec = 0)))
        assertNull(SeekLogic.skipReason(30_000L, cfg(minDurationSec = 0)))
    }

    // ---- guardrail: never past the cap ----

    @Test
    fun `the cap pulls a late target back`() {
        // 60 s into a 2:00 song is 50% — over a 30% cap.
        val target = SeekLogic.computeTarget(120_000L, cfg(mode = "absolute", maxTargetPercent = 30))
        assertEquals(36_000L, target)
    }

    @Test
    fun `the cap also applies to random mode`() {
        val cfg = cfg(mode = "random", randomMin = 50, randomMax = 90, maxTargetPercent = 25)
        repeat(100) {
            val target = SeekLogic.computeTarget(200_000L, cfg)!!
            assertTrue("$target past the 25% cap", target <= 50_000L)
        }
    }

    @Test
    fun `no target ever reaches the very end of the track`() {
        // A 3-minute song with a 100% cap still stops 2 s short.
        val target = SeekLogic.computeTarget(180_000L, cfg(mode = "absolute", absoluteSeconds = 179))
        assertEquals(178_000L, target)
        val uncapped = SeekLogic.computeTarget(180_000L, cfg(relativePercent = 99, maxTargetPercent = 0))
        assertEquals(178_000L, uncapped)
    }

    @Test
    fun `zero turns the cap off`() {
        val target = SeekLogic.computeTarget(120_000L, cfg(mode = "absolute", maxTargetPercent = 0))
        assertEquals(60_000L, target)
    }

    // ---- natural-end detection and the mode cycle ----

    @Test
    fun `a track that ended on its own counts as an auto-next`() {
        assertTrue(SeekLogic.isAutoNext(179_000L, 180_000L))
        assertTrue(SeekLogic.isAutoNext(177_000L, 180_000L))
        assertFalse(SeekLogic.isAutoNext(176_000L, 180_000L))
        assertFalse(SeekLogic.isAutoNext(0L, 0L))
    }

    @Test
    fun `the mode button cycles time to percent to random and back`() {
        assertEquals("relative", SeekLogic.nextMode("absolute"))
        assertEquals("random", SeekLogic.nextMode("relative"))
        assertEquals("absolute", SeekLogic.nextMode("random"))
        assertEquals("absolute", SeekLogic.nextMode("nonsense"))
    }

    // ---- labels shared by the app, the shade and the tile ----

    @Test
    fun `mode labels read like the setting they describe`() {
        assertEquals("1:00", SeekLogic.modeLabel(cfg(mode = "absolute", absoluteSeconds = 60)))
        assertEquals("20%", SeekLogic.modeLabel(cfg(relativePercent = 20)))
        assertEquals("random 15–60%", SeekLogic.modeLabel(cfg(mode = "random")))
        // Min and max are interchangeable, so the label must sort them.
        assertEquals("random 15–60%", SeekLogic.modeLabel(cfg(mode = "random", randomMin = 60, randomMax = 15)))
    }

    @Test
    fun `guardrail labels only mention guardrails that are on`() {
        assertEquals("", SeekLogic.guardrailLabel(cfg()))
        assertEquals(" · >1:30", SeekLogic.guardrailLabel(cfg(minDurationSec = 90)))
        assertEquals(" · ≤80%", SeekLogic.guardrailLabel(cfg(maxTargetPercent = 80)))
        assertEquals(" · >1:30, ≤80%", SeekLogic.guardrailLabel(cfg(minDurationSec = 90, maxTargetPercent = 80)))
        assertEquals("", SeekLogic.guardrailLabel(cfg(maxTargetPercent = 100)))
    }
}
