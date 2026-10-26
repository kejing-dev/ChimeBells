package io.embodit.bianzhong.domain

import io.embodit.bianzhong.domain.model.Bells
import io.embodit.bianzhong.domain.model.Song
import io.embodit.bianzhong.domain.model.Vec3
import io.embodit.bianzhong.domain.model.Zone
import io.embodit.bianzhong.domain.usecase.PlacementUseCase
import io.embodit.bianzhong.domain.usecase.RackPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BellsTest {
    /** design `bells.list`: topM / mouthM / strikeM / mouthRearM, computed with h_hang = 0.22 x tonggao. */
    private val table =
        listOf(
            floatArrayOf(1.461f, 0.728f, 0.838f, 0.660f),
            floatArrayOf(1.453f, 0.758f, 0.862f, 0.693f),
            floatArrayOf(1.445f, 0.787f, 0.885f, 0.725f),
            floatArrayOf(1.432f, 0.831f, 0.921f, 0.774f),
            floatArrayOf(1.424f, 0.860f, 0.945f, 0.807f),
            floatArrayOf(1.412f, 0.904f, 0.980f, 0.856f),
            floatArrayOf(2.103f, 1.634f, 1.704f, 1.589f),
            floatArrayOf(2.097f, 1.656f, 1.722f, 1.614f),
            floatArrayOf(2.089f, 1.685f, 1.745f, 1.647f),
            floatArrayOf(2.083f, 1.707f, 1.763f, 1.671f),
            floatArrayOf(2.074f, 1.736f, 1.787f, 1.704f),
            floatArrayOf(2.070f, 1.751f, 1.799f, 1.720f),
        )

    @Test
    fun layoutMatchesTheDesignTable() {
        for (b in Bells.all) {
            val row = table[b.index]
            assertEquals("top ${b.id}", row[0], Bells.topY(b), 0.002f)
            assertEquals("mouth ${b.id}", row[1], Bells.mouthY(b), 0.002f)
            assertEquals("strike ${b.id}", row[2], Bells.strikeY(b), 0.002f)
            assertEquals("rear ${b.id}", row[3], Bells.mouthRearY(b), 0.002f)
        }
    }

    @Test
    fun tiersClearEachOther() {
        // design `bells.layout.clearance`: B7's mouth rear edge over B1's shank top, >= 0.10 m
        assertTrue("clearance ${Bells.clearance()}", Bells.clearance() >= 0.10f)
        assertEquals(0.128f, Bells.clearance(), 0.002f)
    }

    @Test
    fun leanPutsTheMouthForward() {
        val b = Bells.all[0]
        val mouth = Bells.toRack(b, Vec3(0f, -(b.heightM - Bells.hang(b)), 0f))
        assertTrue(mouth.z > 0.15f)
        val top = Bells.toRack(b, Vec3(0f, Bells.hang(b), 0f))
        assertTrue(top.z < 0f)
    }

    @Test
    fun songsUseTheSetAndHaveTheDesignCounts() {
        assertEquals(36, Song.MOLIHUA.strokes.size)
        assertEquals(42, Song.XIAOXINGXING.strokes.size)
        assertEquals(30, Song.HUANLESONG.strokes.size)
        // Ode to Joy (low) lives on the lower tier: B1 - B4 plus B2's side drum for F3
        assertTrue(Song.HUANLESONG.strokes.all { it.bell <= 3 })
        assertTrue(Song.HUANLESONG.strokes.any { it.bell == 1 && it.zone == Zone.CE })
        // the first stroke of Jasmine is E4 = B8 front drum (front drum first, then the upper tier)
        assertEquals(7, Song.MOLIHUA.strokes[0].bell)
        assertEquals(Zone.ZHENG, Song.MOLIHUA.strokes[0].zone)
        // Twinkle's 4 = F4 = B7 side drum
        assertTrue(Song.XIAOXINGXING.strokes.any { it.note == "F4" && it.bell == 6 && it.zone == Zone.CE })
    }

    @Test
    fun zonesSplitAtAQuarterOfTheWidth() {
        val b = Bells.all[5]
        assertEquals(Zone.ZHENG, Bells.zoneAt(b, 0.08f))
        assertEquals(Zone.CE, Bells.zoneAt(b, 0.09f))
        assertEquals(Zone.CE, Bells.zoneAt(b, -0.12f))
    }

    @Test
    fun rackPoseRoundTrips() {
        val pose = RackPose(Vec3(1f, 0f, -2f), 0.7f, 0.5f)
        val p = Vec3(0.3f, 1.2f, -0.1f)
        val back = pose.toLocal(pose.toStage(p))
        assertEquals(p.x, back.x, 1e-4f)
        assertEquals(p.y, back.y, 1e-4f)
        assertEquals(p.z, back.z, 1e-4f)
        // the footprint centre maps onto the pose centre
        val c = pose.toStage(Bells.FOOT_CENTER)
        assertEquals(0f, c.distanceTo(pose.center), 1e-4f)
    }

    @Test
    fun ghostClampsAndSnapsWithHysteresis() {
        val rule = PlacementUseCase()
        val head = Vec3(0f, 1.6f, 0f)
        val (_, near) = rule.clamp(Vec3(0.3f, 0f, -0.3f), head, 0f)
        assertTrue(near)
        val frame = io.embodit.bianzhong.domain.usecase.SnapFrame(Vec3(0f, 0f, -3f), 0f, 3.2f)
        assertNotNull(rule.snap(Vec3(0.3f, 0f, -3f), 0.1f, listOf(frame), null, 1f))
        assertNull(rule.snap(Vec3(0.4f, 0f, -3f), 0f, listOf(frame), null, 1f))
        // held: lets go only past 0.45 m
        assertNotNull(rule.snap(Vec3(0.4f, 0f, -3f), 0f, listOf(frame), frame, 1f))
        assertNull(rule.snap(Vec3(0.5f, 0f, -3f), 0f, listOf(frame), frame, 1f))
    }
}
