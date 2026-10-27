package io.embodit.bianzhong.domain

import io.embodit.bianzhong.domain.model.Bells
import io.embodit.bianzhong.domain.model.Quat4
import io.embodit.bianzhong.domain.model.Tier
import io.embodit.bianzhong.domain.model.Vec3
import io.embodit.bianzhong.domain.usecase.HeadFrame
import io.embodit.bianzhong.domain.usecase.PanelClearance
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2

/**
 * v0.1.1 UI audit (taste profile D8): the main panel (0.6 m, 18 degrees left, resting 24 - 40 degrees down) must not
 * cover the lower tier's name plates or drums. The plate / mouth geometry is restated here from the scene
 * (`RackView.plateCenter`: 6 cm under the mouth, 0.28 x width high; `BellMeshes.MOUTH_Y`) so the test stays pure.
 */
class PanelClearanceTest {
    private val eye = Vec3(0f, 1.6f, 0f)
    private val panelRange = 0.6f
    private val dpM = 0.0008f * 0.75f
    private val controlHalfH = 168f / 2f * dpM
    private val followHalfH = 264f / 2f * dpM
    private val restRad = Math.toRadians(-24.0).toFloat()
    private val floorRad = Math.toRadians(-40.0).toFloat()

    private fun lowPoints(pose: io.embodit.bianzhong.domain.usecase.RackPose): List<Vec3> =
        Bells.all.filter { it.tier == Tier.LOWER }.flatMap { b ->
            val mouthY = Bells.mouthY(b)
            val forward = (b.heightM - Bells.hang(b)) * kotlin.math.sin(Bells.TILT_RAD)
            val w = maxOf(0.22f * b.heightM, 0.09f)
            listOf(Vec3(b.xM, mouthY - 0.06f - w * 0.28f, forward), Vec3(b.xM, Bells.mouthRearY(b), 0f)).map { pose.toStage(it) }
        }

    /** Drum centres of the lower tier: what the user strikes. */
    private fun drums(pose: io.embodit.bianzhong.domain.usecase.RackPose): List<Vec3> =
        Bells.all.filter { it.tier == Tier.LOWER }.map { b -> pose.toStage(Vec3(b.xM, Bells.strikeY(b), 0.1f)) }

    @Test
    fun panelTopStaysUnderTheLowerTier() {
        for (yaw in listOf(0f, 90f, 180f)) {
            val rot = Quat4.axisAngle(Vec3.UP, Math.toRadians(yaw.toDouble()).toFloat())
            for (distance in listOf(2.5f, 3f, 4f)) {
                val pose = HeadFrame.rackAhead(eye, rot, 0f, 1f, distance)
                val points = lowPoints(pose)
                val lowest = points.minOf { PanelClearance.elevation(eye, it) }
                for ((form, hh) in listOf("control" to controlHalfH, "follow" to followHalfH)) {
                    val cap = PanelClearance.pitchCap(eye, points, hh, panelRange)!!
                    val centre = minOf(restRad, cap).coerceAtLeast(floorRad)
                    val top = centre + atan2(hh, panelRange)
                    val tag = "yaw=$yaw d=$distance $form centre=${Math.toDegrees(centre.toDouble())}"
                    assertTrue("$tag not clamped at the floor", cap > floorRad)
                    assertTrue("$tag top under every plate / mouth", top < lowest)
                    for (d in drums(pose)) assertTrue("$tag drum clear", top < PanelClearance.elevation(eye, d))
                }
            }
        }
    }

    /** The 0.1.0 rest (24 degrees down, no cap) did cover the lower tier with the rack 2.5 m ahead: the case fixed here. */
    @Test
    fun theOldRestOverlapped() {
        val pose = HeadFrame.rackAhead(eye, Quat4.IDENTITY, 0f, 1f, 2.5f)
        val lowest = lowPoints(pose).minOf { PanelClearance.elevation(eye, it) }
        assertTrue(restRad + atan2(followHalfH, panelRange) > lowest)
    }
}
