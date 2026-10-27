package io.embodit.bianzhong.domain

import io.embodit.bianzhong.domain.model.Quat4
import io.embodit.bianzhong.domain.model.Vec3
import io.embodit.bianzhong.domain.usecase.HeadFrame
import io.embodit.bianzhong.domain.usecase.PlacementUseCase
import io.embodit.bianzhong.domain.usecase.PlateFacing
import io.embodit.bianzhong.domain.usecase.RackPose
import io.embodit.bianzhong.domain.usecase.RecenterDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Taste profile D9 / D10 (v0.1.1): content from the head of this moment, plates facing the viewer unmirrored. */
class HeadFrameTest {
    private val floorY = 0f
    private val eye = 1.6f

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()

    /** A head turned [yawDeg] (left positive, 0 = looking along -Z), pitched [pitchDeg] (up positive), rolled [rollDeg]. */
    private fun head(yawDeg: Float, pitchDeg: Float = 0f, rollDeg: Float = 0f): Quat4 =
        Quat4.axisAngle(Vec3.UP, rad(yawDeg)) * Quat4.axisAngle(Vec3.RIGHT, rad(pitchDeg)) * Quat4.axisAngle(Vec3(0f, 0f, 1f), rad(rollDeg))

    private fun level(yawDeg: Float) = Quat4.axisAngle(Vec3.UP, rad(yawDeg)).rotate(Vec3.AHEAD)

    private fun assertVec(msg: String, want: Vec3, got: Vec3, tol: Float = 1e-3f) {
        assertEquals("$msg x", want.x, got.x, tol)
        assertEquals("$msg y", want.y, got.y, tol)
        assertEquals("$msg z", want.z, got.z, tol)
    }

    @Test
    fun headingIgnoresPitchAndRoll() {
        for (yaw in listOf(0f, 37f, 90f, 180f, -90f, -135f)) {
            for (pitch in listOf(-89f, -74f, -60f, -20f, 0f, 30f, 74f, 89f)) {
                // rolling while looking straight down is a turn about the gaze, i.e. a yaw: roll only below 75 degrees
                for (roll in if (kotlin.math.abs(pitch) < 75f) listOf(-30f, 0f, 25f) else listOf(0f)) {
                    assertVec("yaw=$yaw pitch=$pitch roll=$roll", level(yaw), HeadFrame.heading(head(yaw, pitch, roll)), 2e-2f)
                }
            }
        }
        // straight down / straight up: the heading still follows the yaw
        assertVec("down", level(120f), HeadFrame.heading(head(120f, -90f)), 1e-3f)
        assertVec("up", level(120f), HeadFrame.heading(head(120f, 90f)), 1e-3f)
    }

    /** "reset 后他出现在我后面": whatever way the user turned, the rack lands straight ahead and faces them. */
    @Test
    fun rackAheadAfterTurningRound() {
        val pos = Vec3(0.7f, eye, -1.2f)
        for (turn in listOf(0f, 90f, 180f, -90f, 45f, 225f)) {
            for (pitch in listOf(-35f, 0f, 20f)) {
                val rot = head(turn, pitch)
                val pose = HeadFrame.rackAhead(pos, rot, floorY, 1f)
                val ahead = level(turn)
                val to = Vec3(pose.center.x - pos.x, 0f, pose.center.z - pos.z)
                assertEquals("turn=$turn distance", HeadFrame.RACK_AHEAD_M, to.length, 1e-3f)
                assertTrue("turn=$turn in front", to.normalized().dot(ahead) > 0.999f)
                assertEquals("turn=$turn on the floor", floorY, pose.center.y, 1e-5f)
                assertTrue("turn=$turn bearing", HeadFrame.bearing(pos, rot, pose.center) < rad(0.5f))
                // the rack's front (+Z) looks back at the user, level
                val front = pose.rotation.rotate(Vec3(0f, 0f, 1f))
                assertTrue("turn=$turn faces the user", front.dot(-ahead) > 0.999f)
                assertEquals("turn=$turn level", 0f, front.y, 1e-5f)
                // every bell is in front of the user, none behind
                for (b in io.embodit.bianzhong.domain.model.Bells.all) {
                    val p = pose.toStage(Vec3(b.xM, b.hookY, 0f))
                    assertTrue("turn=$turn ${b.id} ahead", (p - pos).dot(ahead) > 1.5f)
                }
            }
        }
    }

    @Test
    fun turnNinetyThenOneEightyMovesTheRackWithTheUser() {
        val pos = Vec3(0f, eye, 0f)
        val first = HeadFrame.rackAhead(pos, head(0f), floorY, 1f)
        val right = HeadFrame.rackAhead(pos, head(-90f), floorY, 1f)
        val back = HeadFrame.rackAhead(pos, head(180f), floorY, 1f)
        assertVec("first", Vec3(0f, 0f, -2.5f), first.center)
        assertVec("turned right", Vec3(2.5f, 0f, 0f), right.center)
        assertVec("turned round", Vec3(0f, 0f, 2.5f), back.center)
        // 0.1.0 kept the old place: after turning round it would be straight behind
        assertFalse(HeadFrame.inFront(pos, head(180f), first.center))
        assertTrue(HeadFrame.inFront(pos, head(180f), back.center))
        assertTrue(HeadFrame.bearing(pos, head(180f), first.center) > HeadFrame.BEHIND_RAD)
    }

    /** The idle controller's ray (or a floor tap) behind the user must not steer the ghost there. */
    @Test
    fun pointerBehindIsNotInFront() {
        val pos = Vec3(0f, eye, 0f)
        val rot = head(30f)
        val place = PlacementUseCase()
        val backRay = place.floorPoint(pos, Quat4.axisAngle(Vec3.UP, rad(210f)).rotate(Vec3(0f, -0.4f, -1f).normalized()), floorY)
        assertFalse(HeadFrame.inFront(pos, rot, backRay))
        val frontRay = place.floorPoint(pos, Quat4.axisAngle(Vec3.UP, rad(50f)).rotate(Vec3(0f, -0.5f, -1f).normalized()), floorY)
        assertTrue(HeadFrame.inFront(pos, rot, frontRay))
    }

    @Test
    fun recenterIsAJumpNotATurn() {
        val d = RecenterDetector()
        val pos = Vec3(0f, eye, 0f)
        // a fast head turn: 180 degrees per second at 60 fps
        var t = 0L
        var yaw = 0f
        repeat(120) {
            assertFalse("turn at $yaw", d.feed(t, pos, head(yaw, -10f)))
            t += 16
            yaw += 3f
        }
        // the system recenter: the heading flips 180 degrees in one frame
        assertTrue(d.feed(t, pos, head(yaw + 180f)))
        t += 16
        // the next frame is ordinary again
        assertFalse(d.feed(t, pos, head(yaw + 181f)))
        // a 0.8 m jump of the head position in one frame
        t += 16
        assertTrue(d.feed(t, pos + Vec3(0.8f, 0f, 0f), head(yaw + 181f)))
        // slow frames (idle emulator, 1 fps) never count
        t += 1000
        assertFalse(d.feed(t, pos, head(yaw + 20f)))
        d.reset()
        assertFalse(d.feed(t + 16, pos, head(yaw + 200f)))
    }

    // ---- plates (D10)

    /** The plate texture's top row sits on top and its left column on the viewer's left: never mirrored or upside down. */
    @Test
    fun plateUvsMatchTheEngine() {
        val c = PlateFacing.corners()
        val top = c.filter { it.position.y > 0f }
        val bottom = c.filter { it.position.y < 0f }
        for (k in top) assertEquals("top samples bitmap row 0", 0f, PlateFacing.bitmapRow(k.v), 1e-6f)
        for (k in bottom) assertEquals("bottom samples the last row", 1f, PlateFacing.bitmapRow(k.v), 1e-6f)
        for (k in c) assertEquals("u grows with +x", k.position.x + 0.5f, k.u, 1e-6f)
        // counter-clockwise seen from +Z: the front is +Z
        val n = (c[1].position - c[0].position).cross(c[2].position - c[0].position)
        assertTrue(n.z > 0f)
        // the gold halves keep the same mapping
        val left = PlateFacing.corners(-0.5f, 0f)
        assertEquals(0f, left[0].u, 1e-6f)
        assertEquals(0.5f, left[1].u, 1e-6f)
        assertEquals(PlateFacing.V_TOP, left[2].v, 1e-6f)
    }

    @Test
    fun platesFaceTheViewerUnmirrored() {
        for (rackYawDeg in listOf(0f, 90f, 180f, -120f)) {
            val rack = RackPose(Vec3(0.3f, 0f, -2f), rad(rackYawDeg), 1f)
            for (viewer in listOf(Vec3(0.3f, 1.6f, 1f), Vec3(-2f, 1.2f, -1f), Vec3(2.5f, 1.7f, -3.5f), Vec3(0.3f, 1.6f, -5f))) {
                for (b in io.embodit.bianzhong.domain.model.Bells.all) {
                    val local = Vec3(b.xM, b.hookY - b.heightM, 0.05f)
                    val headLocal = rack.toLocal(viewer)
                    val world = rack.rotation * PlateFacing.turn(local, headLocal)
                    val centre = rack.toStage(local)
                    val front = world.rotate(Vec3(0f, 0f, 1f))
                    val right = world.rotate(Vec3.RIGHT)
                    val up = world.rotate(Vec3.UP)
                    val look = Vec3(centre.x - viewer.x, 0f, centre.z - viewer.z).normalized()
                    val viewerRight = look.cross(Vec3.UP).normalized()
                    val tag = "rack=$rackYawDeg viewer=$viewer ${b.id}"
                    assertTrue("$tag front to the viewer", front.dot(-look) > 0.999f)
                    assertTrue("$tag +x to the viewer's right (not mirrored)", right.dot(viewerRight) > 0.999f)
                    assertTrue("$tag upright", up.dot(Vec3.UP) > 0.9999f)
                    // right x up = front: a proper rotation, no reflection
                    assertTrue("$tag handedness", right.cross(up).dot(front) > 0.999f)
                }
            }
        }
    }
}
