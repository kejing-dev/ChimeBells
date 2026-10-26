package io.embodit.bianzhong.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import io.embodit.bianzhong.domain.model.Bells
import io.embodit.bianzhong.domain.model.Zone
import io.embodit.bianzhong.platform.ChimeLog

/**
 * The bell strokes (design `audio`): 24 pre-rendered modal-synthesis wavs (`tools/synth_bells.py`,
 * assets/bells, stored uncompressed) played through one SoundPool with 8 voices. A new stroke of a bell
 * does not cut the one still ringing. Left / right volumes of the ringing voices follow the bell's
 * direction from the head every frame ([pan]); distance does not attenuate.
 * Main thread only; [release] on every path that ends the scene.
 */
class BellAudio(context: Context) {

    private class Voice(val stream: Int, val bell: Int, val endsAtMs: Long, var left: Float, var right: Float)

    private val pool: SoundPool =
        SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .build()
    private val sounds = HashMap<String, Int>()
    private val loaded = HashSet<Int>()
    private val voices = ArrayList<Voice>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
        val assets = context.assets
        for (b in Bells.all) for (z in Zone.entries) {
            val key = key(b.index, z)
            runCatching { assets.openFd("bells/$key.wav").use { fd -> sounds[key] = pool.load(fd, 1) } }
                .onFailure { ChimeLog.e("AUDIO load $key failed: $it", it) }
        }
        ChimeLog.i("AUDIO loading ${sounds.size} strokes")
    }

    private fun key(bell: Int, zone: Zone) = "B${bell + 1}_${zone.tag}"

    val ready: Boolean
        get() = loaded.size >= sounds.size && sounds.isNotEmpty()

    /** Plays a stroke at the given left / right volumes; false when the sound is not loaded yet. */
    fun play(bell: Int, zone: Zone, left: Float, right: Float): Boolean {
        val id = sounds[key(bell, zone)] ?: return false
        if (id !in loaded) return false
        val stream = pool.play(id, left, right, 1, 0, 1f)
        if (stream == 0) return false
        val now = SystemClock.elapsedRealtime()
        voices.removeAll { it.endsAtMs < now }
        voices += Voice(stream, bell, now + lengthMs(bell), left, right)
        return true
    }

    /** Updates the volumes of every ringing voice from [volumes] (bell -> left, right). */
    fun pan(volumes: (Int) -> Pair<Float, Float>?) {
        if (voices.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        voices.removeAll { it.endsAtMs < now }
        for (v in voices) {
            val (l, r) = volumes(v.bell) ?: continue
            if (kotlin.math.abs(l - v.left) < 0.02f && kotlin.math.abs(r - v.right) < 0.02f) continue
            v.left = l
            v.right = r
            pool.setVolume(v.stream, l, r)
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
        const val MAX_STREAMS = 8
    }
}
