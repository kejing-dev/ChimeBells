package io.embodit.bianzhong.domain.usecase

import io.embodit.bianzhong.domain.model.Quat4
import io.embodit.bianzhong.domain.model.Vec3

/**
 * The bells' name plates (taste profile D10): a unit quad in its local XY plane, front +Z, turned about the
 * vertical only so it faces the viewer; never pitched, rolled or mirrored.
 *
 * 0.1.0 drew every plate upside down on the headset ("音调的文字也显示反的"): the engine samples a bitmap with
 * v = 0 at its BOTTOM row (the bronze texture already went in flipped for that reason; Light Planner and Sky
 * Dome found the same), while the plate quad gave its top edge v = 0. [UV] is written for the engine's
 * convention and `PlateFacingTest` checks the whole chain.
 */
object PlateFacing {
    /** Bitmap row (0 = top, as a fraction of the height) that the engine samples at [v]. */
    fun bitmapRow(v: Float): Float = 1f - v

    /** Corner v of the quad's bottom (y = -0.5) and top (y = +0.5) edges: v = 1 is the bitmap's top row. */
    const val V_BOTTOM = 0f
    const val V_TOP = 1f

    /** Corner u at local x in -0.5 .. 0.5: u = 0 on the bitmap's left column. */
    fun u(x: Float): Float = x + 0.5f

    /** One mesh corner: local position and texture coordinates. */
    data class Corner(val position: Vec3, val u: Float, val v: Float)

    /**
     * The plate quad's corners for the strip [x0] .. [x1] of the unit plate (the whole plate, or one half for
     * the gold overlays): bottom-left, bottom-right, top-right, top-left, counter-clockwise seen from +Z.
     */
    fun corners(x0: Float = -0.5f, x1: Float = 0.5f): List<Corner> =
        listOf(
            Corner(Vec3(x0, -0.5f, 0f), u(x0), V_BOTTOM),
            Corner(Vec3(x1, -0.5f, 0f), u(x1), V_BOTTOM),
            Corner(Vec3(x1, 0.5f, 0f), u(x1), V_TOP),
            Corner(Vec3(x0, 0.5f, 0f), u(x0), V_TOP),
        )

    /**
     * Local turn of a plate at [center] (rack coordinates) towards the head at [headLocal] (rack coordinates),
     * about the vertical only. The rack itself is only ever turned about the vertical, so the plate stays level.
     */
    fun turn(center: Vec3, headLocal: Vec3?): Quat4 =
        if (headLocal == null) Quat4.IDENTITY else Quat4.axisAngle(Vec3.UP, HeadFrame.facing(center, headLocal))
}
