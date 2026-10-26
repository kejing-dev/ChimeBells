package io.embodit.bianzhong.ui.chime.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.bianzhong.ui.chime.ChimeEvent
import io.embodit.bianzhong.ui.chime.ChimeUiState
import io.embodit.bianzhong.ui.chime.debug.LocalProbePanel
import io.embodit.bianzhong.ui.chime.scene.ChimeScene
import io.embodit.bianzhong.ui.chime.scene.PanelSpec

/**
 * The Stage content: one full-size SpatialView that carries the 3D scene and the panel pool. Every
 * attachment panel is declared here once, with a fixed size, whether it is showing or not; the scene
 * parents each panel entity under its holder and shows or hides the holder.
 *
 * Window model: single panel with popup. The control panel (short, or tall in Follow) is the only
 * standing panel; menu, dialog and snackbar are floating surfaces.
 */
@Composable
internal fun ChimeStage(state: ChimeUiState, scene: ChimeScene, onEvent: (ChimeEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue

    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                },
        attachments = {
            AttachmentPanel(PanelSpec.CONTROL) {
                PoolPanel(PanelSpec.CONTROL, scene, watched = true) { inView -> ControlPanel(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.FOLLOW) {
                PoolPanel(PanelSpec.FOLLOW, scene, watched = true) { inView -> FollowPanel(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.MENU) {
                PoolPanel(PanelSpec.MENU, scene, watched = true) { inView -> MoreMenu(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.DIALOG) {
                PoolPanel(PanelSpec.DIALOG, scene) { inView -> DialogPanel(dialog = if (inView) state.dialog else null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.SNACK) {
                PoolPanel(PanelSpec.SNACK, scene, hugs = true) { inView ->
                    SnackPanel(
                        snack = if (inView) state.snack else null,
                        state = state,
                        applied = scene.panelStretch[PanelSpec.SNACK] ?: 1f,
                        onWidth = { scene.reportWidth(PanelSpec.SNACK, it) },
                    )
                }
            }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel. [content] gets `inView`: the panel has been moved into view. A pooled
 * panel must draw its content only then (what it draws while parked out of sight stays broken on the
 * emulator). [watched]: presses on it hold the lazy follow still. [hugs]: the content is narrower
 * than the panel (snackbar, plates) and reports its width to the scene.
 */
@Composable
private fun PoolPanel(id: String, scene: ChimeScene, watched: Boolean = false, hugs: Boolean = false, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    CompositionLocalProvider(LocalProbePanel provides id) {
        if (watched && !hugs) {
            Box(
                modifier =
                    Modifier.pointerInput(id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) scene.onPanelPointer(true)
                                if (event.type == PointerEventType.Release) scene.onPanelPointer(false)
                            }
                        }
                    }
            ) {
                content(inView)
            }
        } else {
            content(inView)
        }
    }
}
