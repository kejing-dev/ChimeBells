package io.embodit.bianzhong.domain.usecase

import io.embodit.bianzhong.domain.model.Bells
import io.embodit.bianzhong.domain.model.Quat4
import io.embodit.bianzhong.domain.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/** Where the rack stands: footprint centre on the floor and heading (its +Z), plus the display scale. */
data class RackPose(val center: Vec3, val yaw: Float, val scale: Float = 1f) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)

    /** Rack origin (floor point below the lower beam) in Stage space. */
    val origin: Vec3
        get() = center - rotation.rotate(Bells.FOOT_CENTER * scale)

    /** A point in rack coordinates -> Stage. */
    fun toStage(local: Vec3): Vec3 = origin + rotation.rotate(local * scale)

    /** A Stage point -> rack coordinates. */
    fun toLocal(stage: Vec3): Vec3 = rotation.conjugate().rotate(stage - origin) / scale
}

/** One frame of the placement ghost (design `I6`, `I5`, `entities3d.ghostRack`). */
data class GhostPose(val pose: RackPose, val tooClose: Boolean, val snapped: SnapFrame?)

/**
 * Placement rules of the ghost rack: the pointer meets the floor, the footprint centre goes there,
 * clamped to 0.8 - 6 m from the head's floor point (red below 0.8 m); it faces the viewer unless the
 * user turned it; near a snap frame (0.35 m, 20 degrees) it snaps, it lets go at 0.45 m / 25 degrees.
 */
class PlacementUseCase {

    /** The pointer ray against the floor; a ray that does not go down lands 2.5 m ahead. */
    fun floorPoint(origin: Vec3, direction: Vec3, floorY: Float): Vec3 {
        if (direction.y < -0.02f) {
            val t = (floorY - origin.y) / direction.y
            if (t > 0f && t < 30f) return origin + direction * t
        }
        val flat = Vec3(direction.x, 0f, direction.z).let { if (it.length < 0.1f) Vec3.AHEAD else it.normalized() }
        return Vec3(origin.x, floorY, origin.z) + flat * 2.5f
    }

    /** Clamps a floor point into 0.8 - 6 m of the head's floor point; returns the point and whether it was too close. */
    fun clamp(point: Vec3, head: Vec3, floorY: Float): Pair<Vec3, Boolean> {
        val foot = Vec3(head.x, floorY, head.z)
        val d = Vec3(point.x - foot.x, 0f, point.z - foot.z)
        val len = d.length
        val dir = if (len < 1e-3f) Vec3.AHEAD else d / len
        return when {
            len < NEAR_M -> (foot + dir * NEAR_M) to true
            len > FAR_M -> (foot + dir * FAR_M) to false
            else -> Vec3(point.x, floorY, point.z) to false
        }
    }

    /** Heading that turns the rack's front (+Z) towards the head. */
    fun yawFacing(center: Vec3, head: Vec3, fallback: Float = 0f): Float {
        val dx = head.x - center.x
        val dz = head.z - center.z
        if (dx * dx + dz * dz < 1e-4f) return fallback
        return atan2(dx, dz)
    }

    /**
     * The snap frame the footprint is held by: a held frame lets go at 0.45 m or 25 degrees; a new
     * one catches within 0.35 m and 20 degrees (design `I5`).
     */
    fun snap(center: Vec3, yaw: Float, frames: List<SnapFrame>, held: SnapFrame?, scale: Float): SnapFrame? {
        if (held != null) {
            val same = frames.firstOrNull { it.center.distanceTo(held.center) < 0.05f && abs(angle(it.yaw, held.yaw)) < 0.02f }
            if (same != null && flat(center, same.center) < RELEASE_M * maxOf(scale, 0.5f) && abs(angle(yaw, same.yaw)) < RELEASE_RAD) return same
        }
        return frames
            .filter { flat(center, it.center) <= CATCH_M * maxOf(scale, 0.5f) && abs(angle(yaw, it.yaw)) <= CATCH_RAD }
            .minByOrNull { flat(center, it.center) }
    }

    private fun flat(a: Vec3, b: Vec3): Float = Vec3(a.x - b.x, 0f, a.z - b.z).length

    companion object {
        const val NEAR_M = 0.8f
        const val FAR_M = 6f
        const val CATCH_M = 0.35f
        const val RELEASE_M = 0.45f
        val CATCH_RAD = Math.toRadians(20.0).toFloat()
        val RELEASE_RAD = Math.toRadians(25.0).toFloat()

        /** Signed difference a - b wrapped to -pi..pi. */
        fun angle(a: Float, b: Float): Float {
            var d = a - b
            while (d > PI.toFloat()) d -= 2f * PI.toFloat()
            while (d < -PI.toFloat()) d += 2f * PI.toFloat()
            return d
        }
    }
}
