package io.embodit.bianzhong.platform.audio

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Peak (0..1) of a 16-bit PCM wav, for the output-level log and the sum ceiling (v0.1.4). */
internal object WavPeak {
    fun of(input: InputStream): Float {
        val bytes = input.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = b.getInt(p + 4)
            if (id == "data") {
                var peak = 0
                var i = p + 8
                val end = minOf(bytes.size - 1, p + 8 + size)
                while (i + 1 < end) {
                    val s = b.getShort(i).toInt()
                    val a = if (s < 0) -s else s
                    if (a > peak) peak = a
                    i += 2
                }
                return peak / 32767f
            }
            p += 8 + size + (size and 1)
        }
        return 1f
    }
}
