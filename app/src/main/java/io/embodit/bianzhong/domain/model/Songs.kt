package io.embodit.bianzhong.domain.model

/** One stroke of a song: the bell and where to strike it. */
data class Stroke(val bell: Int, val zone: Zone, val note: String)

/**
 * The three public-domain tunes of "Follow" (design `songs.list`), written in jianpu as in the design:
 * digits 1 - 7, a trailing `'` raises an octave, a trailing `,` lowers one; `|` separates phrases.
 * Every stroke is one beat at [bpm].
 */
enum class Song(val tag: String, val base: String, val bpm: Int, val jianpu: String) {
    MOLIHUA("molihua", "C4", 72, "3 3 5 6 1' 1' 6 5 5 6 5 | 3 3 5 6 1' 1' 6 5 5 6 5 | 5 5 5 3 5 6 6 | 3 2 3 5 3 2 1"),
    XIAOXINGXING("xiaoxingxing", "C4", 96, "1 1 5 5 6 6 5 | 4 4 3 3 2 2 1 | 5 5 4 4 3 3 2 | 5 5 4 4 3 3 2 | 1 1 5 5 6 6 5 | 4 4 3 3 2 2 1"),
    HUANLESONG("huanlesong", "C3", 100, "3 3 4 5 5 4 3 2 1 1 2 3 3 2 2 | 3 3 4 5 5 4 3 2 1 1 2 3 2 1 1");

    /** The strokes, resolved to bells by [Bells.find]. */
    val strokes: List<Stroke> by lazy { parse(jianpu, base) }

    val beatMs: Long
        get() = 60_000L / bpm

    companion object {
        fun fromTag(tag: String?): Song? = entries.firstOrNull { it.tag == tag?.lowercase() }

        private val SCALE = listOf("C", "D", "E", "F", "G", "A", "B")

        /** Note name of a jianpu degree over a base "C4" / "C3". */
        fun noteOf(token: String, base: String): String {
            val digit = token.first().digitToInt()
            require(digit in 1..7) { "bad jianpu token $token" }
            var octave = base.last().digitToInt()
            octave += token.count { it == '\'' }
            octave -= token.count { it == ',' }
            return SCALE[digit - 1] + octave
        }

        fun parse(jianpu: String, base: String): List<Stroke> =
            jianpu.split(Regex("\\s+"))
                .filter { it.isNotEmpty() && it != "|" }
                .map { token ->
                    val name = noteOf(token, base)
                    val (bell, zone) = Bells.find(name) ?: error("note $name is not in the set")
                    Stroke(bell.index, zone, name)
                }
    }
}
