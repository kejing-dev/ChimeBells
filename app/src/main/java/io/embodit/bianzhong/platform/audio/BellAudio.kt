package io.embodit.bianzhong.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import io.embodit.bianzhong.domain.model.Bells
import io.embodit.bianzhong.domain.model.Zone
import io.embodit.bianzhong.domain.usecase.StrokeGain
import io.embodit.bianzhong.platform.ChimeLog
import java.util.Locale

/**
 * The bell strokes (design `audio`): 24 pre-rendered modal-synthesis wavs (`tools/synth_bells.py`, assets/bells, stored
 * uncompressed, peaks -1 / -3 dBFS) played through one SoundPool. Left / right volumes of the ringing voices follow the
 * bell's direction from the head every frame ([pan]); distance does not attenuate.
 *
 * v0.1.4 ("连击后好像声音会变小"): a new stroke always plays at master x pan ([StrokeGain.volumes]), so the tenth stroke
 * in a row is as strong as the first. 16 voices; when the tails still ringing would push the sum past -1 dBFS they are
 * ducked ([StrokeGain.duckFor]), and a bell struck again lets its previous voice fade - both ramped over [FADE_MS] in
 * [tick], never cut. Main thread only; [release] on every path that ends the scene.
 */
class BellAudio(context: Context) {

    private class Voice(
        val stream: Int,
        val bell: Int,
        val startMs: Long,
        val lengthMs: Long,
        /** File peak (linear) of the stroke. */
        val filePeak: Float,
        var left: Float,
        var right: Float,
    ) {
        /** Duck applied now, and where it is ramping to. */
        var duck = 1f
        var duckTo = 1f
        var applied = -1f to -1f
    }

    private val pool: SoundPool =
        SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .build()
    private val sounds = HashMap<String, Int>()
    private val peaks = HashMap<String, Float>()
    private val loaded = HashSet<Int>()
    private val voices = ArrayList<Voice>()
    private var position = StrokeGain.DEFAULT
    private var muted = false
    private var lastTickMs = 0L

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
        val assets = context.assets
        for (b in Bells.all) for (z in Zone.entries) {
            val key = key(b.index, z)
            runCatching {
                assets.openFd("bells/$key.wav").use { fd -> sounds[key] = pool.load(fd, 1) }
                peaks[key] = assets.open("bells/$key.wav").use { WavPeak.of(it) }
            }.onFailure { ChimeLog.e("AUDIO load $key failed: $it", it) }
        }
        ChimeLog.i(String.format(Locale.US, "AUDIO loading %d strokes, file peaks %.1f .. %.1f dBFS", sounds.size, StrokeGain.toDb(peaks.values.minOrNull() ?: 0f), StrokeGain.toDb(peaks.values.maxOrNull() ?: 0f)))
    }

    private fun key(bell: Int, zone: Zone) = "B${bell + 1}_${zone.tag}"

    val ready: Boolean
        get() = loaded.size >= sounds.size && sounds.isNotEmpty()

    /** Slider position 0..1 and mute (from the UI state, remembered there). */
    fun setLevel(muted: Boolean, position: Float) {
        if (muted == this.muted && position == this.position) return
        this.muted = muted
        this.position = position
        if (muted) stopAll() else for (v in voices) v.applied = -1f to -1f
    }

    /**
     * Plays a stroke at the pan [left] / [right] (0..1, the louder ear 1); returns the log line, or null when the sound is
     * not loaded yet / muted.
     */
    fun play(bell: Int, zone: Zone, left: Float, right: Float): String? {
        if (muted) return null
        val k = key(bell, zone)
        val id = sounds[k] ?: return null
        if (id !in loaded) return null
        val now = SystemClock.elapsedRealtime()
        voices.removeAll { now - it.startMs > it.lengthMs }
        val (l, r) = StrokeGain.volumes(left, right, position, false)
        val filePeak = peaks[k] ?: 1f
        val newPeak = filePeak * maxOf(l, r)
        // the bell struck again: its previous voice fades (one vibration per bell)
        for (v in voices) if (v.bell == bell) v.duckTo = 0f
        // the tails still ringing make room for the new stroke (ramped), the new stroke is never turned down
        val tails = voices.filter { it.duckTo > 0f }.sumOf { (it.filePeak * maxOf(it.left, it.right) * StrokeGain.master(position, false) * it.duck * StrokeGain.envelope(now - it.startMs, it.lengthMs)).toDouble() }.toFloat()
        val duck = StrokeGain.duckFor(newPeak, tails)
        if (duck < 1f) for (v in voices) v.duckTo = minOf(v.duckTo, v.duck * duck)
        // a full pool: the oldest voice (nearly silent by now) fades instead of being cut by SoundPool
        if (voices.size >= MAX_STREAMS - 1) voices.minByOrNull { it.startMs }?.let { it.duckTo = 0f }
        val stream = pool.play(id, l, r, 1, 0, 1f)
        if (stream == 0) return null
        val length = lengthMs(bell)
        voices += Voice(stream, bell, now, length, filePeak, left, right).also { it.applied = l to r }
        return String.format(Locale.US, "vol=%.2f,%.2f peak=%.1fdBFS ring=%d duck=%.2f tails=%.1fdBFS", l, r, StrokeGain.toDb(newPeak), voices.size, duck, StrokeGain.toDb(tails))
    }

    /** Updates the pan of every ringing voice from [volumes] (bell -> left, right) and runs the fades; once per frame. */
    fun pan(volumes: (Int) -> Pair<Float, Float>?) {
        if (voices.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val step = if (lastTickMs == 0L) 1f else ((now - lastTickMs) / FADE_MS).coerceIn(0f, 1f)
        lastTickMs = now
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (now - v.startMs > v.lengthMs) {
                it.remove()
                continue
            }
            volumes(v.bell)?.let { (l, r) ->
                v.left = l
                v.right = r
            }
            if (v.duck != v.duckTo) v.duck = if (v.duck > v.duckTo) maxOf(v.duckTo, v.duck - step) else minOf(v.duckTo, v.duck + step)
            if (v.duck <= 0f) {
                pool.stop(v.stream)
                it.remove()
                continue
            }
            val (l, r) = StrokeGain.volumes(v.left, v.right, position, muted)
            val out = (l * v.duck) to (r * v.duck)
            if (kotlin.math.abs(out.first - v.applied.first) < 0.01f && kotlin.math.abs(out.second - v.applied.second) < 0.01f) continue
            v.applied = out
            pool.setVolume(v.stream, out.first, out.second)
        }
    }

    fun stopAll() {
        for (v in voices) pool.stop(v.stream)
        voices.clear()
    }

    fun release() {
        stopAll()
        runCatching { pool.release() }
    }

    private fun lengthMs(bell: Int): Long =
        when {
            bell < 3 -> 4500L
            bell < 8 -> 3200L
            else -> 2200L
        }

    companion object {
        /** v0.1.4: 16 voices (0.1.2: 8, and SoundPool cut the oldest when a ninth came in). */
        const val MAX_STREAMS = 16
        private const val FADE_MS = 80f
    }
}
