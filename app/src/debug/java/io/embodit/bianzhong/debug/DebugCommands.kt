package io.embodit.bianzhong.debug

import android.content.Context
import io.embodit.bianzhong.platform.ChimeLog
import io.embodit.bianzhong.ui.chime.ChimeEvent
import io.embodit.bianzhong.ui.chime.debug.ChimeProbe
import io.embodit.bianzhong.ui.chime.scene.PanelSpec
import java.io.File
import java.util.Locale

/**
 * QA hooks of the debug build (design `debugHooks`). The design's `key=value` words, in one string or as
 * separate extras, applied in a fixed order so that combos read naturally:
 *
 *   adb shell "am broadcast -a io.embodit.bianzhong.DEBUG -p io.embodit.bianzhong --es cmd 'mode=follow,song=molihua,step=5 freeze=1'"
 *   adb shell "am broadcast -a io.embodit.bianzhong.DEBUG -p io.embodit.bianzhong --es state S2 --es near 1"
 *
 * Keys: `state=S0|S1|S2|S2a|S3|S6|S13|S14|S17`, `place=auto|wall`, `near=1`, `floor_timeout=<s>`,
 * `wallmin=<m>` (99 = no outlines), `strike=<1-12>,<zheng|ce>`, `hover=<1-12>,<zheng|ce>|0`,
 * `mode=free|follow|pick|demo`, `song=molihua|xiaoxingxing|huanlesong`, `step=<n>`, `done=1`,
 * `scale=1|2`, `lang=zh|en|system`, `labels=0|1`, `ceiling=<m>`, `coach=1`, `coach_reset=1`,
 * `freeze=0|1`, `menu=0|1`, `stop=1`, `replace=1`, `retry=1`, `exit=1`; queries `dump=1` (one JSON line
 * `DUMP {...}`, also files/dump.json), `hmd=1`, `bounds=1`, `planes=1` (planes and snap frames), `park=1`, `recenter=1` (acts as a
 * recenter: a placed rack goes straight ahead, the ghost starts ahead; v0.1.1),
 * `ui=<control>` (presses a panel control by name: more, turn45, mode_free, mode_follow, song_*, listen,
 * follow, stop, again, labels_show, labels_hide, lang_system, lang_zh, lang_en, size_full, size_half,
 * menu_replace, menu_exit, dialog_exit, dialog_retry, chip_half).
 * One log line (tag `Bianzhong`) answers every key; every state change logs `STATE Sxx overlays=...`.
 */
internal object DebugCommands {

    /** Application order: settings first, then the room, then what happens in it, then queries. */
    private val ORDER =
        listOf(
            "lang", "labels", "scale", "mute", "volume", "wallmin", "ceiling", "floor_timeout", "floor_virtual_s", "noplanes", "coach_reset", "freeze_off", "freeze",
            "state", "goto", "place", "near", "mode", "song", "step", "done", "strike", "hover", "coach",
            "floor_nudge", "bring", "move", "recall", "menu", "stop", "replace", "retry", "ui", "dump", "hmd", "bounds", "planes", "park", "recenter", "exit",
        )

    fun run(context: Context, extras: Map<String, String>) {
        val host = ChimeProbe.host
        if (host == null) {
            ChimeLog.i("CMD ignored: the Stage is not running")
            return
        }
        val pairs = LinkedHashMap<String, String>()
        extras["cmd"]?.let { raw -> parse(raw).forEach { (k, v) -> pairs[k] = v } }
        for ((k, v) in extras) if (k != "cmd") pairs[k.lowercase(Locale.US)] = v
        // freeze goes before the room keys: a frozen demo does not run on, a frozen strike holds its swing
        pairs["freeze"]?.let { if (it == "0" || it == "off") { pairs.remove("freeze"); pairs["freeze_off"] = "0" } }
        val keys = pairs.keys.sortedBy { k -> ORDER.indexOf(k).let { if (it < 0) ORDER.size else it } }
        for (k in keys) {
            val v = pairs[k] ?: continue
            try {
                apply(context, host, if (k == "freeze_off") "freeze" else k, v)
            } catch (t: Throwable) {
                ChimeLog.e("CMD $k=$v failed: $t", t)
            }
        }
    }

    /**
     * `a=1,b=2 c=3` -> pairs; a comma or space only separates pairs when a `key=` follows, so
     * `strike=6,zheng` keeps its value. A bare word is a key with value "1" (`dump`, `S5` -> state).
     */
    fun parse(raw: String): List<Pair<String, String>> =
        raw.trim().split(Regex("\\s+"))
            .flatMap { chunk -> chunk.split(Regex(",(?=[A-Za-z_]+=)")) }
            .map { it.trim().trim(',') }
            .filter { it.isNotEmpty() }
            .map { token ->
                val i = token.indexOf('=')
                if (i < 0) {
                    if (token.uppercase(Locale.US).matches(Regex("S\\d+A?"))) "state" to token else token.lowercase(Locale.US) to "1"
                } else token.substring(0, i).lowercase(Locale.US) to token.substring(i + 1)
            }

    private fun apply(context: Context, host: ChimeProbe.Host, key: String, value: String) {
        val scene = host.scene()
        when (key) {
            "ui" -> ChimeLog.i("UI $value pressed=${host.pressControl(value)}")
            "dump" -> {
                val json = host.dumpJson()
                ChimeLog.i("DUMP $json")
                runCatching { File(context.filesDir, "dump.json").writeText(json) }
            }
            "hmd" -> ChimeLog.i(scene?.poseLine() ?: NO_SCENE)
            "bounds" -> {
                scene?.panelLines()?.forEach { ChimeLog.i(it) } ?: ChimeLog.i(NO_SCENE)
                for (panel in PanelSpec.pool) {
                    for ((ctrl, b) in ChimeProbe.controlsOf(panel)) {
                        ChimeLog.i(String.format(Locale.US, "CTRL panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f", panel, ctrl, b.cx, b.cy, b.w, b.h))
                    }
                }
            }
            "planes" -> scene?.planesSummary()?.forEach { ChimeLog.i(it) } ?: ChimeLog.i(NO_SCENE)
            "park" -> ChimeLog.i(scene?.parkPanel() ?: NO_SCENE)
            "recenter" -> scene?.recenter() ?: ChimeLog.i(NO_SCENE)
            else -> host.dispatch(ChimeEvent.Debug(key, value))
        }
    }

    private const val NO_SCENE = "CMD ignored: no scene"
}
