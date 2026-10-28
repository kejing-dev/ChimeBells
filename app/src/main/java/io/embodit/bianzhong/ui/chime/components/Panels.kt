package io.embodit.bianzhong.ui.chime.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Chip
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import io.embodit.bianzhong.R
import io.embodit.bianzhong.domain.model.Bells
import io.embodit.bianzhong.domain.model.Song
import io.embodit.bianzhong.domain.model.Zone
import io.embodit.bianzhong.ui.chime.ChimeEvent
import io.embodit.bianzhong.ui.chime.ChimeUiState
import io.embodit.bianzhong.ui.chime.Room
import io.embodit.bianzhong.ui.chime.debug.probe
import io.embodit.bianzhong.ui.chime.scene.PanelSpec

/** Disabled look of the panel while the room is not ready (design S0 / S1: LocalDisableAlpha 0.3). */
private const val DISABLED_ALPHA = 0.3f

/** One press of Lower / Raise moves the virtual floor this far. */
private const val FLOOR_STEP_M = 0.05f

/**
 * The control panel, short form (400 x 168 dp at 0.75; S0, S1, S2, S2a, S17, S3, S13, S14): title row
 * "编钟" · 1:2 chip · More; the mode segments (or, while placing, Turn 45 and "Lined up"); the hint line.
 * No close key (design `panels.control`).
 */
@Composable
internal fun ControlPanel(state: ChimeUiState, filled: Boolean, onEvent: (ChimeEvent) -> Unit) {
    val (w, h) = PanelSpec.CONTROL_SIZE
    Glass(w.dp, h.dp, refreshKey = listOf(filled, state.room, state.song, state.step, state.english, state.scale, state.snapped, state.tooClose, state.dialog, state.labelsShown, state.language, state.muted, state.floorVirtual)) {
        if (!filled) return@Glass
        val ready = state.room != Room.STARTING && state.room != Room.FINDING && state.dialog == null
        Title(state, ready, onEvent)
        if (state.room == Room.PLACING) {
            Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WordButton("turn45", rs(R.string.action_turn45), { onEvent(ChimeEvent.Turn45Clicked) }, minWidth = 128.dp, height = 40.dp, iconRes = R.drawable.ic_rot_cw)
                if (state.floorVirtual) {
                    // D7 (v0.1.2): the virtual floor is nudged 5 cm per press to meet the real one
                    WordButton("floor_down", rs(R.string.floor_down), { onEvent(ChimeEvent.FloorNudged(-FLOOR_STEP_M)) }, minWidth = 104.dp, height = 40.dp)
                    WordButton("floor_up", rs(R.string.floor_up), { onEvent(ChimeEvent.FloorNudged(FLOOR_STEP_M)) }, minWidth = 104.dp, height = 40.dp)
                } else if (state.snapped) {
                    Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
                    Text(text = rs(R.string.snapped_small), color = PicoTheme.colorScheme.labelPrimary, style = ChimeType.figure, maxLines = 1)
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth().height(40.dp).alpha(if (ready) 1f else DISABLED_ALPHA)) {
                ModeSegments(state, ready, onEvent)
            }
        }
        HintRow(state)
    }
}

/**
 * The control panel in Follow (400 x 264 dp at 0.75; S6 - S9): title row, mode segments, song segments,
 * the action row (Listen · Play along · progress; Stop + target while playing; Again · Listen when done)
 * and the hint line.
 */
@Composable
internal fun FollowPanel(state: ChimeUiState, filled: Boolean, onEvent: (ChimeEvent) -> Unit) {
    val (w, h) = PanelSpec.FOLLOW_SIZE
    Glass(w.dp, h.dp, refreshKey = listOf(filled, state.room, state.song, state.step, state.english, state.scale, state.snapped, state.tooClose, state.dialog, state.labelsShown, state.language, state.muted, state.floorVirtual)) {
        if (!filled) return@Glass
        Title(state, true, onEvent)
        Row(modifier = Modifier.fillMaxWidth().height(40.dp)) { ModeSegments(state, true, onEvent) }
        Row(modifier = Modifier.fillMaxWidth().height(40.dp)) {
            Segments(
                items = listOf("song_molihua" to rs(R.string.song_molihua), "song_xiaoxingxing" to rs(R.string.song_xiaoxingxing), "song_huanlesong" to rs(R.string.song_huanlesong)),
                selected = state.song.ordinal,
                onSelect = { onEvent(ChimeEvent.SongSelected(Song.entries[it])) },
                itemWidth = 122.dp,
                height = 40.dp,
            )
        }
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.room) {
                Room.DEMO, Room.FOLLOW -> {
                    WordButton("stop", rs(R.string.action_stop), { onEvent(ChimeEvent.StopClicked) }, minWidth = 104.dp, iconRes = R.drawable.ic_stop)
                    val line =
                        if (state.room == Room.DEMO) rs(R.string.playing_song, songName(state.song))
                        else state.target?.let { t ->
                            val note = Bells.all[t.bell].note(t.zone)
                            rs(R.string.target_line, note.lvZh, note.name, rs(if (t.zone == Zone.ZHENG) R.string.zone_zheng else R.string.zone_ce))
                        } ?: ""
                    Text(text = line, color = PicoTheme.colorScheme.labelPrimary, style = ChimeType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                Room.DONE -> {
                    WordButton("again", rs(R.string.action_again), { onEvent(ChimeEvent.AgainClicked) }, primary = true, minWidth = 120.dp, iconRes = R.drawable.ic_redo)
                    WordButton("listen", rs(R.string.action_listen), { onEvent(ChimeEvent.ListenClicked) }, minWidth = 104.dp, iconRes = R.drawable.ic_play)
                    Spacer(modifier = Modifier.weight(1f))
                }
                else -> {
                    WordButton("listen", rs(R.string.action_listen), { onEvent(ChimeEvent.ListenClicked) }, minWidth = 104.dp, iconRes = R.drawable.ic_play)
                    WordButton("follow", rs(R.string.action_follow), { onEvent(ChimeEvent.FollowClicked) }, primary = true, minWidth = 120.dp, iconRes = R.drawable.ic_hand)
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            // Follow counts the stroke being asked for ("5/36" = the fifth), the demo the strokes played
            val shownStep = if (state.room == Room.FOLLOW) (state.step + 1).coerceAtMost(state.strokes) else state.step
            Text(text = rs(R.string.progress, shownStep, state.strokes), color = PicoTheme.colorScheme.labelPrimary, style = ChimeType.figure, maxLines = 1, softWrap = false)
        }
        HintRow(state)
    }
}

@Composable
private fun Title(state: ChimeUiState, ready: Boolean, onEvent: (ChimeEvent) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = rs(R.string.title), color = PicoTheme.colorScheme.labelPrimary, style = ChimeType.title, maxLines = 1)
        Spacer(modifier = Modifier.weight(1f))
        if (state.scale < 1f) {
            Chip(label = { Text(text = rs(R.string.chip_half), maxLines = 1, softWrap = false) }, onClick = { onEvent(ChimeEvent.MoreClicked) }, modifier = Modifier.probe("chip_half"))
        }
        // D15 (v0.1.2): mute in plain sight on the main panel, worded (B19), remembered
        WordButton(
            "mute",
            rs(if (state.muted) R.string.label_muted else R.string.action_mute),
            { onEvent(ChimeEvent.MuteClicked) },
            minWidth = 96.dp,
            iconRes = if (state.muted) R.drawable.ic_mute else R.drawable.ic_volume,
        )
        Key("more", R.drawable.ic_more, rs(R.string.action_more), enabled = ready) { onEvent(ChimeEvent.MoreClicked) }
    }
}

@Composable
private fun ModeSegments(state: ChimeUiState, enabled: Boolean, onEvent: (ChimeEvent) -> Unit) {
    Segments(
        items = listOf("mode_free" to rs(R.string.mode_free), "mode_follow" to rs(R.string.mode_follow)),
        selected = if (state.room.follow) 1 else 0,
        onSelect = { if (enabled) onEvent(ChimeEvent.ModeSelected(it == 1)) },
        itemWidth = 184.dp,
        height = 40.dp,
    )
}

/** The hint line (design `stateModel.hintPriority`), with a spinner while starting / finding the floor. */
@Composable
private fun HintRow(state: ChimeUiState) {
    val busy = state.room == Room.STARTING || state.room == Room.FINDING
    val text =
        when {
            state.room == Room.STARTING -> rs(R.string.hint_starting)
            state.room == Room.FINDING || state.room == Room.NO_FLOOR -> rs(R.string.hint_finding)
            state.room == Room.BLOCKED -> rs(R.string.hint_starting)
            state.room == Room.PLACING && state.tooClose -> rs(R.string.hint_near)
            state.room == Room.PLACING && state.floorVirtual -> rs(R.string.hint_virtual)
            state.room == Room.PLACING && state.snapped -> rs(R.string.hint_snapped)
            state.room == Room.PLACING -> rs(R.string.hint_placing)
            state.room == Room.DONE -> rs(R.string.hint_done)
            state.room == Room.FOLLOW -> rs(R.string.hint_follow)
            state.room == Room.DEMO -> rs(R.string.hint_demo)
            state.room == Room.FOLLOW_PICK -> rs(R.string.hint_pick)
            else -> rs(R.string.hint_free)
        }
    Row(modifier = Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(18.dp))
        // too close: primary ink with a warning icon (v0.1.2, D13: the red text on glass measured 1.2:1)
        val near = state.room == Room.PLACING && state.tooClose
        if (near) Icon(painter = painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(18.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Text(
            text = text,
            color = if (near) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary,
            style = ChimeType.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun songName(song: Song): String =
    when (song) {
        Song.MOLIHUA -> rs(R.string.song_molihua_full)
        Song.XIAOXINGXING -> rs(R.string.song_xiaoxingxing_full)
        Song.HUANLESONG -> rs(R.string.song_huanlesong_full)
    }

/** A SegmentControl of [itemWidth] x [height] items; an item with null text shows [leadingIcon]. */
@Composable
internal fun Segments(items: List<Pair<String, String?>>, selected: Int, onSelect: (Int) -> Unit, itemWidth: Dp, height: Dp, leadingIcon: Int? = null) {
    SegmentControl(modifier = Modifier.width(itemWidth * items.size).height(height), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEachIndexed { i, (name, text) ->
            val onClick = { onSelect(i) }
            SegmentItem(
                selected = selected == i,
                onClick = onClick,
                modifier = Modifier.width(itemWidth).height(height).probe(name, onClick),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = {
                    if (text == null && leadingIcon != null) {
                        Icon(painter = painterResource(leadingIcon), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
                    } else {
                        // design-style: inherited-content-color SegmentItem
                        Text(text = text ?: "", maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                    }
                },
            )
        }
    }
}
