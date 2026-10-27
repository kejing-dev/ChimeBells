package io.embodit.bianzhong.domain.usecase

import io.embodit.bianzhong.domain.model.Vec3
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The main panel rests low (24 - 40 degrees under the level gaze), under the lower tier. v0.1.1 UI audit (taste
 * profile D8): with the rack 2.5 m ahead the 24-degree rest put the panel's top edge over the B1 / B2 name plates,
 * and the taller Follow form over the B2 drum, a strike target. The rest elevation is now capped so that the
 * panel's top edge stays [MARGIN_RAD] under the lowest of the rack's low points (plate bottoms, mouths) as
 * seen from the head; the Follower still never goes below its own 40-degree floor.
 */
object PanelClearance {
    val MARGIN_RAD = Math.toRadians(1.5).toFloat()

    /** Elevation of [point] seen from [head], radians (up positive). */
    fun elevation(head: Vec3, point: Vec3): Float = atan2(point.y - head.y, hypot(point.x - head.x, point.z - head.z))

    /**
     * Highest elevation the panel's centre may take: the lowest elevation among [points] minus the margin and the
     * panel's angular half height ([halfHeightM] at [rangeM]). Null without points.
     */
    fun pitchCap(head: Vec3, points: List<Vec3>, halfHeightM: Float, rangeM: Float): Float? {
        if (points.isEmpty()) return null
        val lowest = points.minOf { elevation(head, it) }
        return lowest - MARGIN_RAD - atan2(halfHeightM, rangeM)
    }
}
