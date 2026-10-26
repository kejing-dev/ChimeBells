package io.embodit.bianzhong.domain.model

import kotlin.math.cos
import kotlin.math.sin

/** Where a bell is struck: the centre of the front drum (zhenggu) or its side drum (cegu). */
enum class Zone(val tag: String) {
    ZHENG("zheng"),
    CE("ce");

    companion object {
        fun fromTag(tag: String?): Zone? = entries.firstOrNull { it.tag == tag?.lowercase() }
    }
}

enum class Tier { LOWER, UPPER }

/** One note of the set: name ("C4"), frequency, the Chinese degree name (lü) shown on the plate. */
data class Note(val name: String, val hz: Float, val lvZh: String)

/**
 * One yongzhong of the set (design `bells.list`). Sizes are metres: [heightM] = overall height with the
 * shank (tonggao), [widthM] = between the corners (xianjian), [depthM] = between the drums (gujian).
 * [xM] is the hanging point along the beam, [hookY] its height, both in rack coordinates.
 */
data class BellSpec(
    val index: Int,
    val tier: Tier,
    val zheng: Note,
    val ce: Note,
    val heightM: Float,
    val widthM: Float,
    val depthM: Float,
    val xM: Float,
    val hookY: Float,
) {
    val id: String
        get() = "B${index + 1}"

    fun note(zone: Zone): Note = if (zone == Zone.ZHENG) zheng else ce
}

/**
 * The rack and the twelve bells (design `bells`, gate B default A: two tiers in one line, 3.8 m, low notes
 * on the left). Rack coordinates: origin on the floor below the lower beam, +X along the beam to the
 * viewer's right, +Y up, +Z towards the viewer; hanging points at z = 0.
 *
 * Every bell hangs from the centre of its xuan ring, [HANG_SHARE] of the overall height below the top of
 * the shank (measured on the procedural template, see `BellMeshes`), and leans [TILT_DEG] with its mouth
 * towards the viewer.
 */
object Bells {
    /** h_hang / tonggao of the procedural template: the xuan ring sits 22 % of the height below the shank top. */
    const val HANG_SHARE = 0.22f

    /** Body (without the shank) as a share of the overall height. */
    const val BODY_SHARE = 0.68f

    const val TILT_DEG = 20f

    val TILT_RAD: Float = Math.toRadians(TILT_DEG.toDouble()).toFloat()

    const val LOWER_HOOK_Y = 1.30f
    const val UPPER_HOOK_Y = 2.00f

    /** Rack footprint used for snapping: x in [-1.9, 1.9], z in [-0.40, 0.50] (3.8 x 0.9 m). */
    const val FOOT_HALF_X = 1.9f
    const val FOOT_BACK_Z = -0.40f
    const val FOOT_FRONT_Z = 0.50f

    /** Centre of the footprint in rack coordinates (the ghost follows the pointer with this point). */
    val FOOT_CENTER = Vec3(0f, 0f, (FOOT_BACK_Z + FOOT_FRONT_Z) / 2f)
    const val FOOT_DEPTH = FOOT_FRONT_Z - FOOT_BACK_Z
    const val FOOT_WIDTH = FOOT_HALF_X * 2f

    const val RACK_HEIGHT = 2.25f

    private fun n(name: String, hz: Float, lv: String) = Note(name, hz, lv)

    val all: List<BellSpec> =
        listOf(
            BellSpec(0, Tier.LOWER, n("C3", 130.81f, "宫"), n("E3", 164.81f, "角"), 0.78f, 0.50f, 0.40f, -1.385f, LOWER_HOOK_Y),
            BellSpec(1, Tier.LOWER, n("D3", 146.83f, "商"), n("F3", 174.61f, "清角"), 0.74f, 0.47f, 0.38f, -0.76f, LOWER_HOOK_Y),
            BellSpec(2, Tier.LOWER, n("E3", 164.81f, "角"), n("G3", 196.00f, "徵"), 0.70f, 0.45f, 0.36f, -0.16f, LOWER_HOOK_Y),
            BellSpec(3, Tier.LOWER, n("G3", 196.00f, "徵"), n("B3", 246.94f, "变宫"), 0.64f, 0.41f, 0.33f, 0.41f, LOWER_HOOK_Y),
            BellSpec(4, Tier.LOWER, n("A3", 220.00f, "羽"), n("C4", 261.63f, "宫"), 0.60f, 0.39f, 0.31f, 0.95f, LOWER_HOOK_Y),
            BellSpec(5, Tier.LOWER, n("C4", 261.63f, "宫"), n("E4", 329.63f, "角"), 0.54f, 0.35f, 0.28f, 1.46f, LOWER_HOOK_Y),
            BellSpec(6, Tier.UPPER, n("D4", 293.66f, "商"), n("F4", 349.23f, "清角"), 0.50f, 0.32f, 0.26f, -1.5f, UPPER_HOOK_Y),
            BellSpec(7, Tier.UPPER, n("E4", 329.63f, "角"), n("G4", 392.00f, "徵"), 0.47f, 0.30f, 0.24f, -0.9f, UPPER_HOOK_Y),
            BellSpec(8, Tier.UPPER, n("G4", 392.00f, "徵"), n("B4", 493.88f, "变宫"), 0.43f, 0.28f, 0.22f, -0.3f, UPPER_HOOK_Y),
            BellSpec(9, Tier.UPPER, n("A4", 440.00f, "羽"), n("C5", 523.25f, "宫"), 0.40f, 0.26f, 0.21f, 0.3f, UPPER_HOOK_Y),
            BellSpec(10, Tier.UPPER, n("C5", 523.25f, "宫"), n("E5", 659.26f, "角"), 0.36f, 0.23f, 0.19f, 0.9f, UPPER_HOOK_Y),
            BellSpec(11, Tier.UPPER, n("D5", 587.33f, "商"), n("F5", 698.46f, "清角"), 0.34f, 0.22f, 0.18f, 1.5f, UPPER_HOOK_Y),
        )

    /**
     * Which bell plays a note (design `songs.rule`): the front drum first, then the upper tier.
     * Returns null for a note the set does not have.
     */
    fun find(noteName: String): Pair<BellSpec, Zone>? {
        val zheng = all.filter { it.zheng.name == noteName }.sortedByDescending { it.tier.ordinal }
        if (zheng.isNotEmpty()) return zheng.first() to Zone.ZHENG
        val ce = all.filter { it.ce.name == noteName }.sortedByDescending { it.tier.ordinal }
        if (ce.isNotEmpty()) return ce.first() to Zone.CE
        return null
    }

    /** Hit zone from the hit point in bell coordinates: |x| < 0.25 x width is the front drum. */
    fun zoneAt(spec: BellSpec, localX: Float): Zone = if (kotlin.math.abs(localX) < 0.25f * spec.widthM) Zone.ZHENG else Zone.CE

    /** Distance from the hanging point to the shank top. */
    fun hang(spec: BellSpec): Float = HANG_SHARE * spec.heightM

    /** Height of the body (the bell without its shank). */
    fun body(spec: BellSpec): Float = BODY_SHARE * spec.heightM

    /**
     * A point given in bell coordinates (origin at the hanging point, the bell hanging along -Y, the
     * front drum towards +Z) turned by the lean and an extra swing [swingRad] about the beam axis, and
     * moved to the hanging point: rack coordinates.
     */
    fun toRack(spec: BellSpec, local: Vec3, swingRad: Float = 0f): Vec3 = Vec3(spec.xM, spec.hookY, 0f) + lean(swingRad).rotate(local)

    /** The bell's rotation in rack coordinates: mouth forward by [TILT_DEG] plus the swing. */
    fun lean(swingRad: Float = 0f): Quat4 = Quat4.axisAngle(Vec3.RIGHT, -(TILT_RAD + swingRad))

    // ---- design `bells.layout.hang` figures (checked by the unit test against the design table)

    /** Height of the shank top. */
    fun topY(spec: BellSpec): Float = spec.hookY + hang(spec) * cos(TILT_RAD)

    /** Height of the mouth centre. */
    fun mouthY(spec: BellSpec): Float = spec.hookY - (spec.heightM - hang(spec)) * cos(TILT_RAD)

    /** Height of the mouth's rear edge (towards the wall, its lowest point). */
    fun mouthRearY(spec: BellSpec): Float = mouthY(spec) - spec.depthM / 2f * sin(TILT_RAD)

    /** Height of the drum centre. */
    fun strikeY(spec: BellSpec): Float = mouthY(spec) + 0.22f * body(spec) * cos(TILT_RAD)

    /** Smallest gap between a lower bell's shank top and an upper bell's mouth rear edge it overlaps along x. */
    fun clearance(): Float {
        var best = Float.MAX_VALUE
        for (lo in all.filter { it.tier == Tier.LOWER }) for (up in all.filter { it.tier == Tier.UPPER }) {
            val overlap = kotlin.math.abs(lo.xM - up.xM) < (lo.widthM + up.widthM) / 2f
            if (overlap) best = minOf(best, mouthRearY(up) - topY(lo))
        }
        return best
    }
}
