package io.embodit.bianzhong.domain

import io.embodit.bianzhong.domain.usecase.StrokeGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** v0.1.4 (headset: "声音现在有点小，另外连击后好像声音会变小"; volume bar with three detents). */
class StrokeGainTest {
    private val loudestFile = StrokeGain.db(-1f)

    @Test
    fun mutedIsSilent() {
        assertEquals(0f to 0f, StrokeGain.volumes(1f, 1f, 1f, muted = true))
    }

    @Test
    fun highAndCentreIsTheLoudestFileUnclipped() {
        val (l, r) = StrokeGain.volumes(1f, 1f, 1f, false)
        assertEquals(1f, l, 1e-6f)
        assertEquals(1f, r, 1e-6f)
        assertTrue(StrokeGain.toDb(loudestFile * l) <= -1f + 1e-3f)
    }

    /** The default is clearly louder than 0.1.3's -16 dBFS (master 0.45 x pan 0.6 x a -3 dBFS file). */
    @Test
    fun defaultIsLouderThanBefore() {
        val (l, _) = StrokeGain.volumes(1f, 1f, StrokeGain.DEFAULT, false)
        val now = StrokeGain.toDb(loudestFile * l)
        val before = StrokeGain.toDb(StrokeGain.db(-3f) * 0.45f * 0.6f)
        assertTrue("now=$now before=$before", now - before >= 8f)
        assertTrue(now <= -1f)
    }

    /** A new stroke is never turned down by what is still ringing: the tenth in a row equals the first. */
    @Test
    fun tenStrokesInARowKeepTheirLevel() {
        val one = StrokeGain.volumes(1f, 0.7f, StrokeGain.DEFAULT, false)
        repeat(10) { assertEquals(one, StrokeGain.volumes(1f, 0.7f, StrokeGain.DEFAULT, false)) }
    }

    @Test
    fun tailsAreDuckedToKeepTheSumUnderMinus1dB() {
        assertEquals(1f, StrokeGain.duckFor(0.5f, 0.2f), 1e-6f)
        val d = StrokeGain.duckFor(0.6f, 1.2f)
        assertTrue(d in 0f..1f)
        assertEquals(StrokeGain.CEILING, 0.6f + 1.2f * d, 1e-4f)
        assertEquals(0f, StrokeGain.duckFor(StrokeGain.CEILING, 0.5f), 1e-6f)
    }

    @Test
    fun envelopeDecays60dBOverTheStroke() {
        assertEquals(1f, StrokeGain.envelope(0, 3000), 1e-6f)
        assertEquals(-60f, StrokeGain.toDb(StrokeGain.envelope(3000, 3000)), 0.1f)
    }

    @Test
    fun sliderSnapsToThreeDetentsAndRestsBetween() {
        assertEquals(0.5f, StrokeGain.snap(0.55f), 1e-6f)
        assertEquals(1f, StrokeGain.snap(0.95f), 1e-6f)
        assertEquals(0f, StrokeGain.snap(0.03f), 1e-6f)
        assertEquals(0.3f, StrokeGain.snap(0.3f), 1e-6f)
        assertEquals(1, StrokeGain.detentOf(0.5f))
        assertEquals(-1, StrokeGain.detentOf(0.3f))
        assertTrue(StrokeGain.master(0f, false) < StrokeGain.master(0.5f, false) && StrokeGain.master(0.5f, false) < StrokeGain.master(1f, false))
        assertEquals(StrokeGain.LOW_DB, StrokeGain.toDb(StrokeGain.master(0f, false)), 1e-3f)
    }

    @Test
    fun panKeepsTheLouderEarAtOne() {
        assertEquals(1f to 1f, StrokeGain.pan(0f))
        val (l, r) = StrokeGain.pan(1f)
        assertEquals(1f, r, 1e-6f)
        assertTrue(abs(l - 0.4f) < 1e-6f)
    }
}
