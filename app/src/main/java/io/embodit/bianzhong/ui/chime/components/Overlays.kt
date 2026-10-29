package io.embodit.bianzhong.ui.chime.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.bianzhong.R
import io.embodit.bianzhong.ui.chime.ChimeEvent
import io.embodit.bianzhong.ui.chime.ChimeUiState
import io.embodit.bianzhong.ui.chime.DialogKind
import io.embodit.bianzhong.ui.chime.LanguagePref
import io.embodit.bianzhong.ui.chime.SnackKind
import io.embodit.bianzhong.ui.chime.SnackUi
import io.embodit.bianzhong.ui.chime.debug.probe
import io.embodit.bianzhong.ui.chime.scene.PanelSpec
import kotlin.math.abs

private const val FADE_IN_MS = 150

/**
 * The More menu (256 x 344 dp at 0.75, S10), popping up above the More key: note names, language,
 * size, volume (v0.1.2, D15), then Bring here (D14), Move rack and Exit. Segments act in place and leave the
 * menu open; Bring here and Move rack close it.
 */
@Composable
internal fun MoreMenu(state: ChimeUiState, filled: Boolean, onEvent: (ChimeEvent) -> Unit) {
    val (w, h) = PanelSpec.MENU_SIZE
    Glass(w.dp, h.dp, corner = 24.dp, material = Material.Thick, padding = 12.dp, spacing = 8.dp, refreshKey = listOf(filled, state.room, state.song, state.step, state.english, state.scale, state.snapped, state.tooClose, state.dialog, state.labelsShown, state.language, state.muted, state.volume)) {
        if (!filled) return@Glass
        MenuRow(rs(R.string.menu_labels)) {
            Segments(
                items = listOf("labels_show" to rs(R.string.labels_show), "labels_hide" to rs(R.string.labels_hide)),
                selected = if (state.labelsShown) 0 else 1,
                onSelect = { onEvent(ChimeEvent.LabelsSelected(it == 0)) },
                itemWidth = 76.dp,
                height = 40.dp,
            )
        }
        MenuRow(rs(R.string.menu_language)) {
            Segments(
                items = listOf("lang_system" to null, "lang_zh" to rs(R.string.lang_zh), "lang_en" to rs(R.string.lang_en)),
                selected = when (state.language) {
                    LanguagePref.SYSTEM -> 0
                    LanguagePref.ZH -> 1
                    LanguagePref.EN -> 2
                },
                onSelect = { onEvent(ChimeEvent.LanguageSelected(listOf(LanguagePref.SYSTEM, LanguagePref.ZH, LanguagePref.EN)[it])) },
                itemWidth = 50.dp,
                height = 40.dp,
                leadingIcon = R.drawable.ic_globe,
            )
        }
        MenuRow(rs(R.string.menu_size)) {
            Segments(
                items = listOf("size_full" to rs(R.string.size_full), "size_half" to rs(R.string.size_half)),
                selected = if (state.scale < 1f) 1 else 0,
                onSelect = { onEvent(ChimeEvent.ScaleSelected(if (it == 1) 0.5f else 1f)) },
                itemWidth = 76.dp,
                height = 40.dp,
            )
        }
        MenuRow(rs(R.string.menu_volume)) {
            Segments(
                items = listOf("vol_low" to rs(R.string.vol_low), "vol_mid" to rs(R.string.vol_mid), "vol_high" to rs(R.string.vol_high)),
                selected = state.volume,
                onSelect = { onEvent(ChimeEvent.VolumeSelected(it)) },
                itemWidth = 50.dp,
                height = 40.dp,
            )
        }
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically) {
            WordButton("menu_bring", rs(R.string.menu_bring), { onEvent(ChimeEvent.BringClicked) }, minWidth = 152.dp, height = 44.dp, iconRes = R.drawable.ic_hand, enabled = state.room.placed, modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WordButton("menu_replace", rs(R.string.menu_replace), { onEvent(ChimeEvent.ReplaceClicked) }, minWidth = 152.dp, height = 44.dp, iconRes = R.drawable.ic_move, modifier = Modifier.weight(1f))
            Key("menu_exit", R.drawable.ic_exit, rs(R.string.action_exit)) { onEvent(ChimeEvent.ExitClicked) }
        }
    }
}

@Composable
private fun MenuRow(label: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, color = PicoTheme.colorScheme.labelPrimary, style = ChimeType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(66.dp))
        content()
    }
}

/**
 * The dialogs (self-built attachment panel, 400 x 236 dp at 1.125, 0.9 m ahead): "No floor found"
 * (S13) and "Spatial features unavailable" (S14). In a Stage the system dialog composes nothing
 * (Spatial Ruler finding), so the card is built to its metrics: Thick, padding 32, corners 32.
 */
@Composable
internal fun DialogPanel(dialog: DialogKind?, onEvent: (ChimeEvent) -> Unit) {
    val (w, h) = PanelSpec.DIALOG_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (dialog == null) return@Box
        val fade = remember(dialog) { Animatable(0f) }
        LaunchedEffect(dialog) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        val ink = PicoTheme.colorScheme.labelPrimary
        Column(
            modifier =
                Modifier
                    .size(w.dp, h.dp)
                    .graphicsLayer { alpha = fade.value }
                    .clip(RoundedCornerShape(32.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .padding(28.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painter = painterResource(if (dialog == DialogKind.NO_FLOOR) R.drawable.ic_floor else R.drawable.ic_unavailable), contentDescription = null, modifier = Modifier.size(28.dp), tint = ink)
                Text(
                    text = if (dialog == DialogKind.NO_FLOOR) rs(R.string.dialog_nofloor_title) else rs(R.string.dialog_blocked_title),
                    color = ink,
                    style = ChimeType.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (dialog == DialogKind.NO_FLOOR) rs(R.string.dialog_nofloor_body) else rs(R.string.dialog_blocked_body),
                color = PicoTheme.colorScheme.labelSecondary,
                style = ChimeType.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                WordButton("dialog_exit", rs(R.string.action_exit), { onEvent(ChimeEvent.ExitClicked) }, minWidth = 120.dp, iconRes = R.drawable.ic_exit)
                WordButton("dialog_retry", rs(R.string.action_retry), { onEvent(ChimeEvent.RetryClicked) }, primary = true, minWidth = 120.dp, iconRes = R.drawable.ic_retry)
            }
        }
    }
}

@Composable
private fun snackText(s: SnackUi, state: ChimeUiState): String =
    when (s.kind) {
        SnackKind.COACH -> rs(R.string.snack_coach)
        SnackKind.NO_WALL -> rs(R.string.snack_no_wall, if (state.scale < 1f) "1.3 m" else "2.5 m")
        SnackKind.LOW_CEILING -> rs(R.string.snack_low_ceiling)
        SnackKind.SNAPPED -> rs(R.string.snack_snapped)
        SnackKind.VIRTUAL_FLOOR -> rs(R.string.snack_virtual_floor)
        SnackKind.RACK_BEHIND -> rs(R.string.snack_rack_behind)
    }

/**
 * The snackbar (head-locked attachment panel 14 degrees above the view centre, 560 x 56 dp at 1.125):
 * 28 dp corners, Material.Thick, an icon and one line. The capsule takes the width of its message
 * inside the fixed-size panel ([applied]: the scene squeezes the panel to the capsule's width and the
 * capsule is drawn stretched back).
 */
@Composable
internal fun SnackPanel(snack: SnackUi?, state: ChimeUiState, applied: Float = 1f, onWidth: (Float) -> Unit = {}) {
    val (w, h) = PanelSpec.SNACK_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (snack == null) return@Box
        val density = LocalDensity.current
        val panelPx = with(density) { w.dp.toPx() }
        val ink = PicoTheme.colorScheme.labelPrimary
        val fade = remember(snack.id, snack.kind) { Animatable(0f) }
        LaunchedEffect(snack.id, snack.kind) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        Row(
            modifier =
                Modifier
                    .widthIn(min = 240.dp, max = w.dp)
                    .defaultMinSize(minHeight = 48.dp)
                    .onSizeChanged { onWidth(it.width / density.density) }
                    .graphicsLayer {
                        val own = size.width / panelPx
                        scaleX = 1f / applied
                        alpha = if (abs(applied - own) <= own * 0.12f) fade.value else 0f
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .probe("snackbar")
                    .padding(start = 16.dp, top = 4.dp, end = 20.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter =
                    painterResource(
                        when (snack.kind) {
                            SnackKind.COACH -> R.drawable.ic_bell
                            SnackKind.NO_WALL -> R.drawable.ic_wall
                            SnackKind.LOW_CEILING -> R.drawable.ic_ceiling
                            SnackKind.SNAPPED -> R.drawable.ic_check
                            SnackKind.VIRTUAL_FLOOR -> R.drawable.ic_floor
                            SnackKind.RACK_BEHIND -> R.drawable.ic_move
                        }
                    ),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = ink,
            )
            Text(text = snackText(snack, state), color = ink, style = ChimeType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
