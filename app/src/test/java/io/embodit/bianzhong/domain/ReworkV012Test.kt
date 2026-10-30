package io.embodit.bianzhong.domain

import io.embodit.bianzhong.domain.model.Bells
import io.embodit.bianzhong.domain.model.Quat4
import io.embodit.bianzhong.domain.model.Vec3
import io.embodit.bianzhong.domain.usecase.HeadFrame
import io.embodit.bianzhong.domain.usecase.PlacementUseCase
import io.embodit.bianzhong.domain.usecase.RackPose
import io.embodit.bianzhong.domain.usecase.RecenterDetector
import io.embodit.bianzhong.domain.usecase.StrokeGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.2 rework, QA round 2: q2-1 (placeholder head pose), q2-3 (footprint clearance, D14), q2-4 (gain, D15). */
class ReworkV012Test {
    private val rule = PlacementUseCase()
    private val eye = Vec3(0f, 1.6f, 0f)

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()

    // ---- q2-1

    @Test
    fun originPlaceholderIsNotAHead() {
        assertFalse(HeadFrame.plausible(Vec3.ZERO, Quat4.IDENTITY))
        assertFalse(HeadFrame.plausible(Vec3(Float.NaN, 1.6f, 0f), Quat4.IDENTITY))
        assertTrue(HeadFrame.plausible(eye, Quat4.IDENTITY))
        assertTrue(HeadFrame.plausible(Vec3(0.01f, 0f, 0f), Quat4.IDENTITY))
    }

    /** S18: facing +Z, the provider's origin frame before ON_STOP read as a 180-degree jump and moved the rack behind. */
    @Test
    fun placeholderFrameIsNoRecenter() {
        val d = RecenterDetector()
        val facingPlusZ = Quat4.axisAngle(Vec3.UP, rad(180f))
        assertFalse(d.feed(1000L, eye, facingPlusZ))
        assertFalse(d.feed(1011L, eye, facingPlusZ))
        // the placeholder: origin, identity (= facing -Z), 11 ms later
        assertFalse(d.feed(1022L, Vec3.ZERO, Quat4.IDENTITY))
        // the real head again
        assertFalse(d.feed(1033L, eye, facingPlusZ))
        // a real recenter still counts
        assertTrue(d.feed(1044L, Vec3(0f, 1.6f, 0f), Quat4.IDENTITY))
    }

    // ---- q2-3

    @Test
    fun clearanceIsToTheNearestFootprintPoint() {
        // rack straight ahead, front facing the head: front edge 0.45 m in front of the footprint centre
        val pose = RackPose(Vec3(0f, 0f, -1.25f), 0f)
        val head = Vec3(0f, 1.6f, 0f)
        assertEquals(1.25f - (Bells.FOOT_FRONT_Z - (Bells.FOOT_BACK_Z + Bells.FOOT_FRONT_Z) / 2f), rule.clearance(pose, head), 1e-3f)
        assertEquals(0.8f, rule.clearance(pose, head), 1e-3f)
        // standing inside the footprint
        assertEquals(0f, rule.clearance(RackPose(Vec3(0f, 0f, 0f), 0f), head), 1e-4f)
        // an end of the 3.8 m rack (turned side-on, its end 0.5 m from the head)
        val sideOn = RackPose(Vec3(0f, 0f, -(Bells.FOOT_HALF_X + 0.5f)), rad(90f))
        assertEquals(0.5f, rule.clearance(sideOn, head), 0.05f)
    }

    /** 0.1.1 cleared the centre at 0.8 m: the front edge came to 0.35 m. Now the footprint is pushed out to 0.8 m. */
    @Test
    fun pushClearKeepsEveryPartOfTheRack08mAway() {
        val head = Vec3(0f, 1.6f, 0f)
        for (yawDeg in listOf(0f, 30f, 90f, 135f, 180f)) {
            for (centre in listOf(Vec3(0f, 0f, -0.8f), Vec3(0.3f, 0f, -0.2f), Vec3(0f, 0f, 0f), Vec3(1.5f, 0f, -0.5f))) {
                val start = RackPose(centre, rad(yawDeg))
                val (p, moved) = rule.pushClear(start, head)
                assertEquals("yaw=$yawDeg centre=$centre moved", rule.clearance(start, head) < PlacementUseCase.NEAR_M - 0.01f, moved)
                assertTrue("yaw=$yawDeg centre=$centre clear=${rule.clearance(p, head)}", rule.clearance(p, head) >= PlacementUseCase.NEAR_M - 0.02f)
            }
        }
        val far = RackPose(Vec3(0f, 0f, -2.5f), 0f)
        assertEquals(far to false, rule.pushClear(far, head))
        // the half-size rack is measured at its own size
        val half = RackPose(Vec3(0f, 0f, -1.03f), 0f, 0.5f)
        assertFalse(rule.pushClear(half, head).second)
    }

    @Test
    fun rackAheadIsClear() {
        for (yaw in listOf(0f, 90f, 180f, -90f)) {
            val pose = HeadFrame.rackAhead(eye, Quat4.axisAngle(Vec3.UP, rad(yaw)), 0f, 1f)
            assertTrue(rule.clearance(pose, eye) >= PlacementUseCase.NEAR_M)
        }
    }

    // ---- q2-4

    // ---- q2-4 is superseded by v0.1.4's gain model (StrokeGainTest)
}
