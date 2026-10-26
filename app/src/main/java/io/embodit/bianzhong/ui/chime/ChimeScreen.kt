package io.embodit.bianzhong.ui.chime

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.SpatialSoundEffect
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.bianzhong.platform.ChimeLog
import io.embodit.bianzhong.ui.chime.components.ChimeStage
import io.embodit.bianzhong.ui.chime.components.LocalChimeResources
import io.embodit.bianzhong.ui.chime.components.localizedResources
import io.embodit.bianzhong.ui.chime.debug.ChimeProbe
import io.embodit.bianzhong.ui.chime.scene.ChimeFrameSystem
import io.embodit.bianzhong.ui.chime.scene.ChimeScene

/**
 * The one screen of the app, living in a Mixed Stage. It wires the three parts together: the
 * ViewModel holds the state, the [ChimeScene] turns state into entities and reports taps, drags, the
 * stick and the head, and [ChimeStage] declares the panels.
 */
@Composable
fun ChimeScreen() {
    val context = LocalContext.current
    val viewModel: ChimeViewModel = viewModel(factory = ChimeViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current

    remember(context) {
        ChimeProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { ChimeScene(viewModel::onEvent) }

    DisposableEffect(scene) {
        registerSystem<ChimeFrameSystem>()
        ChimeProbe.host =
            object : ChimeProbe.Host {
                override fun dispatch(event: ChimeEvent) = viewModel.onEvent(event)

                override fun uiState(): ChimeUiState = viewModel.state.value

                override fun dumpJson(): String = viewModel.dumpJson()

                override fun scene(): ChimeProbe.SceneProbe = scene

                override fun pressControl(name: String): Boolean = ChimeProbe.click(name)
            }
        ChimeLog.i("STAGE enter")
        onDispose {
            ChimeProbe.host = null
            unregisterSystem<ChimeFrameSystem>()
            scene.detach()
            ChimeLog.i("STAGE disposed")
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_STOP -> viewModel.onEvent(ChimeEvent.WentBackground)
                        Lifecycle.Event.ON_START -> viewModel.onEvent(ChimeEvent.CameForeground)
                        else -> Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    val audio = LocalAudioEffectPlayer.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect: ChimeUiEffect ->
            when (effect) {
                ChimeUiEffect.ExitApp -> {
                    ChimeLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { ChimeLog.e("STAGE close failed: $it", it) }
                }
                is ChimeUiEffect.Strike -> scene.strike(effect.bell, effect.zone, effect.local, effect.user)
                ChimeUiEffect.Wave -> scene.wave()
                ChimeUiEffect.StopSounds -> scene.stopSounds()
                ChimeUiEffect.Click -> runCatching { audio.playSystem(SpatialSoundEffect.OpClick) }.onFailure { ChimeLog.w("SOUND failed: $it") }
            }
        }
    }

    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    CompositionLocalProvider(LocalChimeResources provides resources) {
        ChimeStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
