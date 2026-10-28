package io.embodit.bianzhong.domain.usecase

import kotlin.math.pow

/**
 * Loudness of the bell strokes (taste profile D15, v0.1.2, QA q2-4). 0.1.1 played the pan value straight as the
 * volume (up to 1.0) with up to 8 voices piling up. Now every voice is pan x master x a polyphony share, so a run
 * of strokes stays near one stroke's loudness instead of adding up.
 */
object StrokeGain {
    /** Master volume per level: low, medium (the default, restrained), high. */
    val LEVELS = floatArrayOf(0.25f, 0.45f, 0.7f)

    /** Total of all ringing voices allowed relative to one voice at full pan (a soft limiter). */
    const val CEILING = 1f

    fun master(level: Int, muted: Boolean): Float = if (muted) 0f else LEVELS[level.coerceIn(0, LEVELS.size - 1)]

    /** Share of one voice when [ringing] voices sound at once: n^-0.7, so n voices sum to n^0.3 (8 voices: 1.9). */
    fun share(ringing: Int): Float = if (ringing <= 1) 1f else ringing.toFloat().pow(-0.7f)

    /** Final left / right volumes of one voice, clamped to 0..[CEILING]. */
    fun volumes(left: Float, right: Float, level: Int, muted: Boolean, ringing: Int): Pair<Float, Float> {
        val k = master(level, muted) * share(ringing)
        return (left * k).coerceIn(0f, CEILING) to (right * k).coerceIn(0f, CEILING)
    }
}
