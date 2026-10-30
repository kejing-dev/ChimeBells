package io.embodit.bianzhong.domain.usecase

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Loudness of the bell strokes (taste D15; v0.1.4 after the headset: "声音现在有点小，另外连击后好像声音会变小").
 *
 * 0.1.2 scaled every new stroke by n^-0.7 of the voices still ringing, so a run of strokes got quieter and quieter
 * (8 ringing voices: -12.7 dB), and played at master 0.45 x pan 0.6 (about -16 dBFS). Now:
 * - a new stroke always plays at [volumes]: master x pan, the louder ear at 1.0 - the same level whether it is the first
 *   stroke or the tenth in a row;
 * - the strokes themselves peak at -1 dBFS (lower tier) / -3 dBFS (upper tier), `tools/synth_bells.py`;
 * - when the tails still ringing would push the sum past [CEILING] (-1 dBFS), the older voices are ducked ([duckFor]),
 *   ramped, never cut; the new stroke is not touched;
 * - a bell struck again lets its previous voice fade (a bell takes one vibration at a time).
 */
object StrokeGain {
    /** Volume slider 0..1 with detents low / medium (default) / high. */
    val DETENTS = floatArrayOf(0f, 0.5f, 1f)
    const val DEFAULT = 0.5f

    /** Slider positions within this of a detent snap to it when let go. */
    const val SNAP = 0.08f

    /** The slider is linear in dB: low -10.5 dB (0.30), medium -5.2 dB (0.55), high 0 dB (1.0). */
    const val LOW_DB = -10.5f

    /** -1 dBFS: no stroke, and no sum of strokes, goes above this. */
    val CEILING: Float = db(-1f)

    fun db(v: Float): Float = 10f.pow(v / 20f)

    fun toDb(a: Float): Float = if (a <= 1e-6f) -120f else 20f * log10(a)

    /** Master gain of slider position [u] (0..1); 0 when muted. */
    fun master(u: Float, muted: Boolean): Float = if (muted) 0f else db(LOW_DB * (1f - u.coerceIn(0f, 1f)))

    /** The nearest detent when [u] is within [SNAP] of one, else [u]. */
    fun snap(u: Float): Float {
        val d = DETENTS.minByOrNull { abs(it - u) } ?: return u
        return if (abs(d - u) <= SNAP) d else u.coerceIn(0f, 1f)
    }

    /** Index of the detent [u] sits on (0 low, 1 medium, 2 high), or -1 between detents. */
    fun detentOf(u: Float): Int = DETENTS.indexOfFirst { abs(it - u) < 0.005f }

    /** Old 0.1.2 level (0 / 1 / 2) -> slider position. */
    fun fromLevel(level: Int): Float = DETENTS[level.coerceIn(0, DETENTS.size - 1)]

    /**
     * Left / right pan from the bell's bearing [s] (-1 far left .. 1 far right): the louder ear at 1.0, the other down
     * to 0.4 at the side (a bell straight ahead is 1.0 / 1.0).
     */
    fun pan(s: Float): Pair<Float, Float> {
        val k = s.coerceIn(-1f, 1f)
        return (1f - 0.6f * maxOf(k, 0f)) to (1f + 0.6f * minOf(k, 0f))
    }

    /** Final left / right volumes of a new stroke: pan x master, never above 1. */
    fun volumes(left: Float, right: Float, u: Float, muted: Boolean): Pair<Float, Float> {
        val m = master(u, muted)
        return (left * m).coerceIn(0f, 1f) to (right * m).coerceIn(0f, 1f)
    }

    /** Envelope of a stroke [ageMs] after it started, [lengthMs] long (60 dB down at its end), relative to its peak. */
    fun envelope(ageMs: Long, lengthMs: Long): Float = if (ageMs <= 0) 1f else exp(-ln(1000f) * ageMs / lengthMs.coerceAtLeast(1L))

    /**
     * The duck for the voices already ringing when a stroke of peak [newPeak] (linear, after master and pan) comes in on
     * top of tails whose current peaks add up to [tails]: 1 when the sum stays under [CEILING], else the factor that
     * brings the tails down to the room left (0 when the new stroke alone fills it).
     */
    fun duckFor(newPeak: Float, tails: Float): Float {
        if (tails <= 1e-6f || newPeak + tails <= CEILING) return 1f
        return ((CEILING - newPeak) / tails).coerceIn(0f, 1f)
    }
}
