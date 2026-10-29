package io.embodit.bianzhong.ui.chime.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import io.embodit.bianzhong.domain.model.Bells
import io.embodit.bianzhong.domain.model.PointerRay
import io.embodit.bianzhong.domain.model.Quat4
import io.embodit.bianzhong.domain.model.SurfaceKind
import io.embodit.bianzhong.domain.model.Tier
import io.embodit.bianzhong.domain.model.Vec3
import io.embodit.bianzhong.domain.model.Zone
import io.embodit.bianzhong.domain.usecase.HeadFrame
import io.embodit.bianzhong.domain.usecase.PanelClearance
import io.embodit.bianzhong.domain.usecase.PlacementUseCase
import io.embodit.bianzhong.domain.usecase.RackPose
import io.embodit.bianzhong.domain.usecase.RecenterDetector
import io.embodit.bianzhong.domain.usecase.SnapFrame
import io.embodit.bianzhong.domain.usecase.WallSnapUseCase
import io.embodit.bianzhong.platform.ChimeGraph
import io.embodit.bianzhong.platform.ChimeLog
import io.embodit.bianzhong.platform.audio.BellAudio
import io.embodit.bianzhong.platform.sense.PlaneTracker
import io.embodit.bianzhong.platform.tracking.HeadPose
import io.embodit.bianzhong.ui.chime.ChimeEvent
import io.embodit.bianzhong.ui.chime.ChimeUiState
import io.embodit.bianzhong.ui.chime.Hover
import io.embodit.bianzhong.ui.chime.Room
import io.embodit.bianzhong.ui.chime.debug.ChimeProbe
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: ChimeScene? = null
}

/** The one per-frame ECS system of the app. */
class ChimeFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/**
 * The 3D side of the app: the rack and its twelve bells, the placement ghost with its footprint and the
 * snap outlines, decals, plates, ripples, the tap targets, the bell sounds, and the placement of the
 * pre-built panels. Compose state arrives through [render] (idempotent); per-frame work (ghost, snapping,
 * thumbstick, swings, breathing, hover ray, plates, ripples, panning) happens in [onFrame] only.
 * Everything started in [attach] is stopped in [detach]. Main thread only.
 */
class ChimeScene(private val sink: (ChimeEvent) -> Unit) : ChimeProbe.SceneProbe {

    /** Panel id -> the panel is in view: a pooled panel composes its content only then. */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Share of its panel's width that a panel's content fills (snackbar). */
    val panelStretch = mutableStateMapOf<String, Float>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val planeTracker = ChimeGraph.planes
    private val headTracker = ChimeGraph.head
    private val controller = ChimeGraph.controller
    private val snapRule = WallSnapUseCase()
    private val placeRule = PlacementUseCase()

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var contentProbe: Entity? = null
    private var materials: SceneMaterials? = null
    private var shared: SharedResources? = null
    private var bronzeTexture: TextureResource? = null
    private var colliders: PlaneColliders? = null
    private var rig: PanelRig? = null
    private var rack: RackView? = null
    private var ghost: GhostView? = null
    private var outlines: SnapOutlines? = null
    private var ripples: Ripples? = null
    private var audio: BellAudio? = null
    private var attached = false

    private val follower = Follower("control", Follower.MAIN_OFFSET, MAIN_HALF_EXTENTS, Follower.MAIN_LEAN)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null

    private var ui = ChimeUiState()
    private var head = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var lastPanelPressMs = NEVER
    private var panelPressed = false
    private var dialogPlaced = false
    private var lastLost = false
    private val recenter = RecenterDetector()
    private var clearanceKey: Any? = null
    private var frontCheckAtMs = NEVER
    private var frontCheckUntilMs = NEVER
    private var inBackground = false

    // ---- room facts (planes)
    private var lastPlaneCount = -1
    private var lastPlanesEmitMs = 0L
    private var floorY: Float? = null
    /** The detected floor; [floorY] is this, or the virtual stand-in (D7, v0.1.2). */
    private var realFloorY: Float? = null
    /** Head height minus 1.55 m, taken once when the virtual floor comes in. */
    private var virtualBaseY: Float? = null
    private var planesWereBlocked = false
    private var ceilingY: Float? = null
    private var frames: List<SnapFrame> = emptyList()
    private var framesKey = ""
    private var framesSent = -1
    private var planesFailed = false

    // ---- ghost
    private var ghostCenter: Vec3? = null
    private var ghostYaw = 0f
    private var userTurned = false
    private var held: SnapFrame? = null
    private var shownPose: RackPose? = null
    private var tooClose = false
    private var reportedSnap = false
    private var reportedNear = false
    private var stickRate = 0f
    private var lastFrameMs = NEVER
    private var frameGapMs = 0L
    private var lastPlateMs = 0L
    private var turnSeen = 0
    private var turnFrom = 0f
    private var turnTo = 0f
    private var turnStartMs = NEVER
    private var triggerDown = false

    // ---- the placed rack is locked (v0.1.3): only [Move] / Bring here move it; see-through when walked into (D14)
    private var rackFaded = false

    // ---- bells
    private val impulses = Array(12) { ArrayList<Pair<Long, Float>>() }
    private val flashes = LongArray(12) { NEVER }
    private val flashZone = arrayOfNulls<Zone>(12)
    private val flashLeft = BooleanArray(12)
    private var pointerHover: Hover? = null
    private var pointerHoverLeft = false
    private var coachStartMs = NEVER
    private val pendingWave = ArrayList<Pair<Long, Int>>()

    /** The control panel's pointer: a press holds the lazy follow still. */
    fun onPanelPointer(pressed: Boolean) {
        panelPressed = pressed
        lastPanelPressMs = SystemClock.elapsedRealtime()
    }

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin (0.256 m off in z for a
        // full-size view). Move the root once so that its local space equals Stage space.
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        ChimeLog.i("STAGE root offset=${ChimeLog.pos(offset.domain())}")

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        for (id in PanelSpec.pool) {
            val (w, h) = PanelSpec.size(id)
            if (id == PanelSpec.SNACK) rig.head(id, SNACK_HEAD_OFFSET, PanelSpec.FLOATING_SCALE, w, h) else rig.world(id, w, h)
        }
        headTracker.start()
        controller.start()
        ActiveScene.scene = this
        ChimeGraph.autoPlacement = { _ -> autoPose() }
        bindPanels(attachments)
        follower.reset()
        // plane detection starts with the Stage (the emulator room takes 20 - 45 s), in parallel with the paced build
        syncPlanes(ui)
        buildJob =
            scope.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) ChimeLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
    }

    /**
     * Textures, materials, meshes and views, one mesh / one bell per paced step ([frame]): many mesh
     * resources or entities in one go stall the idle emulator (factory finding on PICO dynamic meshes).
     */
    private suspend fun build(root: Entity, t0: Long) {
        val context = ChimeGraph.appContext
        audio = context?.let { c -> runCatching { BellAudio(c) }.onFailure { ChimeLog.e("AUDIO init failed: $it", it) }.getOrNull() }
        val sh = SharedResources()
        shared = sh
        bronzeTexture = runCatching { sh.keep(TextureResource.create(BellArt.bronzeTexture())) }.onFailure { ChimeLog.e("SCENE bronze texture failed: $it", it) }.getOrNull()
        val m = SceneMaterials(sh, bronzeTexture)
        materials = m
        frame()
        colliders =
            PlaneColliders(root).also {
                it.buildBackstop()
                planeTracker.all.forEach(it::upsert)
            }
        frame()
        val bell = sh.keep(BellMeshes.template())
        frame()
        val dz = sh.keep(BellMeshes.decal(-0.5f, 0.5f, "decalZheng"))
        frame()
        val dr = sh.keep(BellMeshes.decal(0.5f, 1.0f, "decalCeRight"))
        frame()
        val dl = sh.keep(BellMeshes.decal(-1.0f, -0.5f, "decalCeLeft"))
        frame()
        val lacquer = sh.keep(BellMeshes.rackLacquer())
        frame()
        val band = sh.keep(BellMeshes.rackBand())
        frame()
        val hooks = sh.keep(BellMeshes.rackHooks())
        frame()
        val plate = sh.keep(BellMeshes.quadUv("plate"))
        frame()
        val halfL = sh.keep(BellMeshes.quadUv("plateHalfL", -0.5f, 0f))
        frame()
        val halfR = sh.keep(BellMeshes.quadUv("plateHalfR", 0f, 0.5f))
        frame()
        val ring = sh.keep(MeshFactory.ring(0.42f, 0.5f, "ripple"))
        frame()
        val footDashed = sh.keep(BellMeshes.rectOutline(Bells.FOOT_WIDTH, Bells.FOOT_DEPTH, 0.04f, 0.10f, 0.06f, "footDashed"))
        frame()
        val footSolid = sh.keep(BellMeshes.rectOutline(Bells.FOOT_WIDTH, Bells.FOOT_DEPTH, 0.04f, 0f, 0f, "footSolid"))
        frame()
        val outlineDashed = sh.keep(BellMeshes.rectOutline(Bells.FOOT_WIDTH, Bells.FOOT_DEPTH, 0.02f, 0.10f, 0.06f, "outlineDashed"))
        frame()
        val outlineSolid = sh.keep(BellMeshes.rectOutline(Bells.FOOT_WIDTH, Bells.FOOT_DEPTH, 0.02f, 0f, 0f, "outlineSolid"))
        frame()
        val meshes = ChimeMeshes(bell, dz, dr, dl, lacquer, band, hooks, plate, halfL, halfR, ring, footDashed, footSolid, outlineDashed, outlineSolid)
        val rackView = RackView(root, m, meshes).also { it.setShown(false) }
        rack = rackView
        frame()
        // one bell (pivot, body, decals, collider, plate) per paced step: twelve at once stalls the idle emulator
        while (!rackView.complete) {
            rackView.addBell()
            frame()
        }
        ripples = Ripples(rackView.root, m, ring)
        val ghostView = GhostView(root, m, meshes).also { it.setShown(false) }
        ghost = ghostView
        frame()
        while (!ghostView.complete) {
            ghostView.addBell()
            ghostView.addBell()
            frame()
        }
        outlines = SnapOutlines(root, m, meshes)
        frame()
        // plate textures: one per step, the per-frame queue keeps them in the current language afterwards
        if (context != null) {
            for (i in rackView.platesToDraw(ui.english)) {
                rackView.buildPlate(i, context.resources, ui.english)
                frame()
            }
        }
        attached = true
        ChimeLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui, force = true)
        sink(ChimeEvent.SceneReady)
    }

    /**
     * Gives the main thread back between build steps: one engine frame while frames are fast (headset),
     * a long pause while the idle emulator renders at 1 - 2 fps (every mesh / entity batch waits for the
     * render thread there; back-to-back steps blocked the main thread into "isn't responding" in other
     * factory apps).
     */
    private suspend fun frame() {
        delay(if (frameGapMs > FAST_FRAME_MS) SLOW_STEP_MS else FRAME_MS)
    }

    /** Called from `SpatialView(update = ...)`: parents freshly composed panel entities under their holders. */
    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        val complete = rig.bind(attachments, PanelSpec.pool, SystemClock.elapsedRealtime())
        if (!complete) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        buildJob?.cancel()
        buildJob = null
        if (ActiveScene.scene === this) ActiveScene.scene = null
        ChimeGraph.autoPlacement = null
        main.removeCallbacksAndMessages(null)
        planeTracker.stop()
        headTracker.stop()
        controller.stop()
        audio?.release()
        audio = null
        panelShown.clear()
        panelStretch.clear()
        runCatching { rig?.release() }
        ripples?.destroy()
        rack?.destroy()
        ghost?.destroy()
        outlines?.destroy()
        colliders?.clear()
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        bronzeTexture = null
        stageRoot = null
        contentProbe = null
        content = null
        materials = null
        colliders = null
        rig = null
        rack = null
        ghost = null
        outlines = null
        ripples = null
        hasHead = false
        lastPlaneCount = -1
        dialogPlaced = false
        ChimeLog.i("SCENE detached")
    }

    // =========================================================================================
    // model -> entities (called when the UI state changes)
    // =========================================================================================

    fun render(state: ChimeUiState, force: Boolean = false) {
        val previous = ui
        ui = state
        syncPlanes(state)
        if (!attached) return
        val rackView = rack ?: return
        val placed = state.room.placed && state.rack != null
        rackView.setShown(placed)
        if (placed) {
            if (force || previous.rack != state.rack) rackView.place(state.rack!!)
        } else {
            pointerHover = null
            ripples?.clear()
        }
        val placing = state.room == Room.PLACING
        ghost?.setShown(placing && state.floorKnown)
        if (!placing) {
            outlines?.hideAll()
            if (previous.room == Room.PLACING) resetGhost()
        } else if (previous.room != Room.PLACING) {
            resetGhost()
            // a move (v0.1.3) starts the ghost where the locked rack stood, keeping its heading
            state.moveStart?.let { m ->
                ghostCenter = m.center
                ghostYaw = m.yaw
                userTurned = true
            }
        }
        if (state.turnSerial != turnSeen) {
            turnSeen = state.turnSerial
            if (placing) {
                userTurned = true
                turnFrom = ghostYaw
                turnTo = ghostYaw - (PI / 4).toFloat()
                turnStartMs = SystemClock.elapsedRealtime()
            }
        }
        if (state.coach && !previous.coach) coachStartMs = SystemClock.elapsedRealtime()
        if (!state.coach) coachStartMs = NEVER
        audio?.setLevel(state.muted, state.volume)
        if (state.muted && !previous.muted) audio?.stopAll()
        if (previous.scale != state.scale || previous.wallMin != state.wallMin || previous.forceWall != state.forceWall) framesKey = ""
        if (previous.dialog != null && state.dialog == null) planesFailed = false
        syncPanels(state)
    }

    /**
     * ON_STOP / ON_START, straight from the lifecycle observer (v0.1.2, QA q2-1). 0.1.1 looked for the
     * background -> foreground transition in [render], but Compose does not recompose while the activity is
     * stopped, so the scene never saw `background = true` and the check after coming back never ran.
     */
    fun onForeground(foreground: Boolean) {
        val now = SystemClock.elapsedRealtime()
        inBackground = !foreground
        recenter.reset()
        if (!foreground) {
            audio?.stopAll()
            frontCheckAtMs = NEVER
            ChimeLog.i("RECENTER watch paused (background)")
        } else {
            // the head may come from a recentred frame (late by up to ~0.8 s, pico-dev-knowledge "warm resume ...
            // pre-recenter HMD"), so look again 1.5 s later, on a real head sample, and bring a rack left behind back ahead
            frontCheckAtMs = now + FRONT_CHECK_DELAY_MS
            frontCheckUntilMs = now + FRONT_CHECK_GIVE_UP_MS
            ChimeLog.i("RECENTER check in ${FRONT_CHECK_DELAY_MS}ms (foreground)")
        }
    }

    /** Plane detection runs while wanted and in the foreground; a paused tracker keeps what it saw. */
    private fun syncPlanes(state: ChimeUiState) {
        if (stageRoot == null) return
        val want = state.planesOn && !state.background && !state.planesBlocked
        if (want && !planeTracker.running) {
            val ok =
                planeTracker.start(
                    PlaneTracker.Listener { id, surface, _ ->
                        if (surface != null) colliders?.upsert(surface) else colliders?.remove(id)
                    }
                )
            if (!ok && !planesFailed) {
                planesFailed = true
                sink(ChimeEvent.PlaneTrackingFailed)
            }
        } else if (!want && planeTracker.running) {
            planeTracker.pause()
        }
    }

    private fun resetGhost() {
        ghostCenter = null
        userTurned = false
        held = null
        shownPose = null
        turnStartMs = NEVER
        stickRate = 0f
    }

    private fun syncPanels(state: ChimeUiState) {
        val rig = rig ?: return
        if (state.room.follow) rig[PanelSpec.CONTROL]?.hide() else rig[PanelSpec.FOLLOW]?.hide()
        if (!state.menuOpen) rig[PanelSpec.MENU]?.hide()
        val dialog = rig[PanelSpec.DIALOG]
        if (state.dialog != null) {
            if (!dialogPlaced && hasHead) {
                val flat = HeadFrame.heading(head.rotation)
                // 10 degrees under the level gaze (D10: never look up), clear of the low control panel (24 - 40 down)
                val dir = flat * PanelSpec.DIALOG_COS + Vec3(0f, -PanelSpec.DIALOG_SIN, 0f)
                dialog?.show(head.position + dir * PanelSpec.DIALOG_DISTANCE_M, PanelSpec.FLOATING_SCALE)
                dialogPlaced = true
            }
        } else {
            dialog?.hide()
            dialogPlaced = false
        }
        val snack = rig[PanelSpec.SNACK]
        if (state.snack != null) snack?.show(null, null) else snack?.hide()
    }

    /** Width of a hugging panel's content, dp; called by the panel. */
    fun reportWidth(id: String, widthDp: Float) {
        val slot = rig?.get(id) ?: return
        if (widthDp <= 0f) return
        slot.widthFactor = widthDp / slot.widthDp
        if (panelStretch[id] != slot.widthFactor) panelStretch[id] = slot.widthFactor
    }

    // =========================================================================================
    // strokes (effects from the ViewModel)
    // =========================================================================================

    /** A bell sounds and swings; [local] = where it was hit (bell coordinates), null = the zone's centre. */
    fun strike(bell: Int, zone: Zone, local: Vec3?, user: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val spec = Bells.all.getOrNull(bell) ?: return
        val left = local != null && local.x < 0f
        val (point, normal) = if (local != null) RackView.surfacePoint(spec, local) else RackView.zonePoint(spec, zone == Zone.CE)
        val amp = if (spec.tier == Tier.LOWER) SWING_LOWER_RAD else SWING_UPPER_RAD
        impulses[bell].add(now to amp)
        if (impulses[bell].size > 6) impulses[bell].removeAt(0)
        flashes[bell] = now
        flashZone[bell] = zone
        flashLeft[bell] = left
        ripples?.fire(bell, point, normal, now)
        val (l, r) = volumes(bell) ?: (1f to 1f)
        val played = audio?.play(bell, zone, l, r) ?: false
        ChimeLog.i(String.format(Locale.US, "strike=%s,%s,pan=%.2f,%.2f user=%b audio=%s", spec.id, zone.tag, l, r, user, if (ui.muted) "muted" else if (played) "ok" else "pending"))
    }

    /** Follow done: a silent gold ripple on every bell, left to right, 0.1 s apart. */
    fun wave() {
        val now = SystemClock.elapsedRealtime()
        pendingWave.clear()
        val order = Bells.all.sortedBy { it.xM + if (it.tier == Tier.UPPER) 0.01f else 0f }
        order.forEachIndexed { k, spec -> pendingWave += (now + k * WAVE_STEP_MS) to spec.index }
    }

    fun stopSounds() {
        audio?.stopAll()
    }

    /** Left / right volumes from the bell's bearing seen from the head: +-60 degrees -> 0.2 .. 1.0. */
    private fun volumes(bell: Int): Pair<Float, Float>? {
        val pose = ui.rack ?: return null
        if (!hasHead) return null
        val spec = Bells.all[bell]
        val c = pose.toStage(Bells.toRack(spec, Vec3(0f, BellMeshes.boxCenterY(spec.heightM), 0f)))
        val v = c - head.position
        val a = atan2(v.dot(head.right), v.dot(head.forward))
        val s = (a / PAN_RANGE_RAD).coerceIn(-1f, 1f)
        return (0.6f - 0.4f * s) to (0.6f + 0.4f * s)
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == NEVER) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.25f)
        if (lastFrameMs != NEVER) frameGapMs = now - lastFrameMs
        lastFrameMs = now
        headTracker.pose()?.let {
            head = it
            hasHead = true
            // only real samples in the foreground count (the provider's origin frames came 0.5 s before ON_STOP)
            if (!inBackground && it.plausible && recenter.feed(now, it.position, it.rotation)) onRecentered("jump", HeadFrame.FRONT_RAD)
        }
        if (frontCheckAtMs != NEVER && now >= frontCheckAtMs && !inBackground) {
            if (hasHead && head.plausible && headTracker.validAtMs >= frontCheckAtMs - FRONT_CHECK_DELAY_MS) {
                frontCheckAtMs = NEVER
                onRecentered("resume", HeadFrame.BEHIND_RAD)
            } else if (now > frontCheckUntilMs) {
                frontCheckAtMs = NEVER
                ChimeLog.i("RECENTER resume check dropped: no real head sample")
            }
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(head.view)
        val state = ui
        val hold = panelPressed || (lastPanelPressMs != NEVER && now - lastPanelPressMs < PANEL_HOLD_MS) || state.menuOpen || state.dialog != null
        if (hasHead) clearPanel(state, now)
        if (hasHead && head.position.length > 1e-3f) follower.follow(head, planeTracker.all, planeTracker.count, now, hold)
        placeFamily(state, rig)
        if (state.dialog != null && !dialogPlaced) syncPanels(state)
        if (!attached) return

        pollTracking(now)
        pollPlanes(now)
        syncFloor(state)
        if (state.room == Room.PLACING) {
            ghostFrame(state, now, dt)
            triggerFrame(state)
        } else {
            triggerDown = false
        }
        if (state.room.placed && state.rack != null) {
            fadeFrame(state)
            bellsFrame(state, now)
        }
        redrawPlates(state, now)
        rig.faceViewer(head.position)
    }

    /**
     * The main panel rests under the rack's lower tier (v0.1.1 UI audit, D8): its designed elevation is capped
     * below the lowest plate / mouth seen from the head, and it slides there again when the rack moves or the
     * panel changes form (the Follow form is taller).
     */
    private fun clearPanel(state: ChimeUiState, now: Long) {
        val pose = state.rack?.takeIf { state.room.placed }
        val form = if (state.room.follow) PanelSpec.FOLLOW else PanelSpec.CONTROL
        if (pose == null) {
            follower.pitchCap = Float.POSITIVE_INFINITY
        } else {
            val (_, hh) = PanelSpec.halfM(form, PanelSpec.MAIN_SCALE)
            follower.pitchCap = PanelClearance.pitchCap(head.position, lowPoints(pose), hh, Follower.MAIN_OFFSET.length) ?: Float.POSITIVE_INFINITY
        }
        val key = pose to form
        if (key != clearanceKey) {
            val first = clearanceKey == null
            clearanceKey = key
            if (!first && pose != null) follower.park(head, planeTracker.all, now)
        }
    }

    /** The lower tier's lowest points in Stage space: each plate's bottom edge and mouth. */
    private fun lowPoints(pose: RackPose): List<Vec3> =
        Bells.all.filter { it.tier == Tier.LOWER }.flatMap { spec ->
            val plate = RackView.plateCenter(spec, 0f) - Vec3(0f, RackView.plateWidth(spec) * RackView.PLATE_ASPECT / 2f, 0f)
            val mouth = Bells.toRack(spec, Vec3(0f, BellMeshes.MOUTH_Y * spec.heightM, 0f))
            listOf(pose.toStage(plate), pose.toStage(mouth))
        }

    /** The control panel (short or follow form) and the menu above it ride on the lazy follow. */
    private fun placeFamily(state: ChimeUiState, rig: PanelRig) {
        if (!hasHead) return
        val pose = follower.pose
        val rot = follower.rotation
        val k = PanelSpec.MAIN_SCALE
        val shownId = if (state.room.follow) PanelSpec.FOLLOW else PanelSpec.CONTROL
        rig[shownId]?.place(pose.position, rot, k)
        val (hw, hh) = PanelSpec.halfM(shownId, k)
        if (state.menuOpen) {
            val (mw, mh) = PanelSpec.halfM(PanelSpec.MENU, k)
            rig[PanelSpec.MENU]?.place(pose.position + rot.rotate(Vec3(hw - mw, hh + 0.012f + mh, 0.012f)), rot, k)
        }
    }

    /**
     * The controller's ray, or null with no controller: aiming never uses the head's forward (taste profile D1,
     * v0.1.2). Without a controller the system pointer (gaze + pinch) aims: taps and hover come from it.
     */
    private fun pointerRay(): PointerRay? = controller.sample()?.ray

    /**
     * The ghost (design `I6`, `I5`, `I3`): footprint centre on the pointer's floor point (smoothed 0.12 s,
     * 0.8 - 6 m from the head, red below 0.8 m), facing the viewer until the user turns it (thumbstick,
     * Turn 45), snapping to the outline frames.
     */
    private fun ghostFrame(state: ChimeUiState, now: Long, dt: Float) {
        val g = ghost ?: return
        val fy = floorY
        if (fy == null || !hasHead) {
            g.setShown(false)
            outlines?.hideAll()
            return
        }
        g.setShown(true)
        refreshFrames(state)
        // the ghost starts straight ahead of the head (D9); the pointer steers it only while its floor point is in
        // front (an idle controller on the headset still reports a pose, which put the 0.1.0 rack behind the user)
        val ahead = HeadFrame.rackAhead(head.position, head.rotation, fy, state.scale)
        // without a controller the ghost waits where it is (ahead); a gaze + pinch on the floor places it there
        val pointed = pointerRay()?.let { ray -> placeRule.floorPoint(ray.origin, ray.direction, fy) }
        val raw = if (pointed != null && HeadFrame.inFront(head.position, head.rotation, pointed)) pointed else ghostCenter ?: ahead.center
        val (clamped, near) = placeRule.clamp(raw, head.position, fy)
        // D14 (v0.1.2): the footprint's nearest point, not its centre, stays 0.8 m from the head; pushed out = red
        val aimYaw = if (userTurned) ghostYaw else placeRule.yawFacing(clamped, head.position, ghostYaw)
        val (cleared, pushed) = placeRule.pushClear(RackPose(clamped, aimYaw, state.scale), head.position)
        val target = cleared.center
        val c0 = ghostCenter
        val center = if (c0 == null) ahead.center else if (dt <= 0f) target else c0.lerp(target, 1f - exp(-dt / FOLLOW_TAU_S))
        ghostCenter = center
        tooClose = near || pushed || state.forcedNear

        // turning: the stick (dead zone 0.15, 90 degrees per second at full tilt, smoothed 0.15 s) and Turn 45
        val x = controller.sample()?.stickX ?: 0f
        val wantRate = if (abs(x) > STICK_DEAD) -(x - STICK_DEAD * kotlin.math.sign(x)) / (1f - STICK_DEAD) * STICK_RATE_RAD_S else 0f
        stickRate += (wantRate - stickRate) * (if (dt > 0f) 1f - exp(-dt / STICK_TAU_S) else 0f)
        if (abs(stickRate) > 0.01f) {
            if (!userTurned) {
                userTurned = true
                ChimeLog.i("STICK start")
            }
            ghostYaw += stickRate * dt
        } else if (wantRate == 0f) {
            stickRate = 0f
        }
        if (turnStartMs != NEVER) {
            val q = ((now - turnStartMs) / TURN_MS).coerceIn(0f, 1f)
            ghostYaw = turnFrom + (turnTo - turnFrom) * Ease.inOutCubic(q)
            if (q >= 1f) turnStartMs = NEVER
        }
        if (!userTurned) ghostYaw = placeRule.yawFacing(center, head.position, ghostYaw)

        // snapping
        val snap =
            if (state.forceWall) forcedFrame()
            else if (tooClose) null
            else placeRule.snap(center, ghostYaw, frames, held, state.scale)?.takeIf { f -> placeRule.clearance(RackPose(f.center, f.yaw, state.scale), head.position) >= PlacementUseCase.NEAR_M - 0.02f }
        if (snap != null && held == null) ChimeLog.i("SNAP on wall=${ChimeLog.f(snap.wallLength)} center=${ChimeLog.pos(snap.center)}")
        if (snap == null && held != null) ChimeLog.i("SNAP off")
        held = snap
        val wanted = if (snap != null) RackPose(snap.center, snap.yaw, state.scale) else RackPose(center, ghostYaw, state.scale)
        val prev = shownPose
        val shown =
            if (prev == null || dt <= 0f || state.freeze) wanted
            else {
                val k = 1f - exp(-dt / SNAP_TAU_S)
                RackPose(prev.center.lerp(wanted.center, k), prev.yaw + PlacementUseCase.angle(wanted.yaw, prev.yaw) * k, state.scale)
            }
        shownPose = shown
        g.place(shown)
        g.setTone(if (tooClose) GhostView.Tone.RED else if (snap != null) GhostView.Tone.BRIGHT else GhostView.Tone.NORMAL)
        g.setSolid(snap != null)

        // outlines: nearest brightens from 40 % (1.0 m) to 100 % (0.35 m); the held one is solid
        val o = outlines
        if (o != null) {
            for (k in 0 until WallSnapUseCase.MAX_FRAMES) {
                val f = frames.getOrNull(k)
                if (f == null) {
                    o.hide(k)
                    continue
                }
                val d = Vec3(center.x - f.center.x, 0f, center.z - f.center.z).length
                val op = (0.4f + 0.6f * ((1.0f - d) / 0.65f)).coerceIn(0.4f, 1f)
                o.show(k, f.center, f.yaw, state.scale, op, solid = snap != null && snap.center.distanceTo(f.center) < 0.05f)
            }
        }
        val snapped = snap != null
        if (snapped != reportedSnap || tooClose != reportedNear) {
            reportedSnap = snapped
            reportedNear = tooClose
            sink(ChimeEvent.GhostChanged(snapped, tooClose))
        }
    }

    /**
     * Debug `place=wall`: the longest wall's frame among those ahead of the viewer (1.5 - 6 m in front),
     * so the snapped ghost is in the picture; else the longest anywhere. Only that frame stays drawn.
     */
    private fun forcedFrame(): SnapFrame? {
        val f = Vec3(head.forward.x, 0f, head.forward.z).let { if (it.length < 0.1f) Vec3.AHEAD else it.normalized() }
        val ahead = frames.filter { val d = (it.center - head.position).dot(f); d in 1.5f..6f }
        val pick = (ahead.ifEmpty { frames }).maxByOrNull { it.wallLength } ?: return null
        frames = listOf(pick)
        return pick
    }

    /** The trigger's press edge places the rack (design I2: no repeat while held). */
    private fun triggerFrame(state: ChimeUiState) {
        val s = controller.sample()
        val down = s != null && (s.pressed || s.trigger > 0.6f)
        if (down && !triggerDown && state.dialog == null) {
            ChimeLog.i("TAP trigger place")
            sink(ChimeEvent.PlaceRequested(placeablePose()))
        }
        triggerDown = down
    }

    /** The ghost's pose when it may be put down, else null (too close, no floor yet). */
    private fun placeablePose(): RackPose? {
        if (tooClose) return null
        val pose = shownPose?.let { p -> held?.let { RackPose(it.center, it.yaw, p.scale) } ?: p } ?: return null
        // the shown ghost may still be easing out of the clearance: never put the rack down inside it
        return if (hasHead && placeRule.clearance(pose, head.position) < PlacementUseCase.NEAR_M - 0.02f) null else pose
    }

    /** Snap frames from the merged walls, recomputed when the planes, the size or the threshold change. */
    private fun refreshFrames(state: ChimeUiState) {
        val fy = floorY ?: return
        val key = "${planeTracker.count}|${state.scale}|${state.wallMin}|${state.forceWall}|${ChimeLog.f(fy)}"
        if (key == framesKey) return
        framesKey = key
        val planes = if (state.planesBlocked) emptyList() else planeTracker.all
        val walls = snapRule.walls(planes, fy, snapRule.floorCenter(planes))
        val min = if (state.forceWall) 0f else state.wallMin
        frames = snapRule.frames(walls, state.scale, head.position, min, if (state.forceWall) 64 else WallSnapUseCase.MAX_FRAMES)
        ChimeLog.i("WALLS merged=${walls.size} lengths=${walls.joinToString(",") { ChimeLog.f(it.length) }} frames=${frames.size} min=${ChimeLog.f(min)}")
        if (frames.size != framesSent) {
            framesSent = frames.size
            sink(ChimeEvent.PlanesChanged(floorY, ceilingY, frames.size, virtual = realFloorY == null && floorY != null))
        }
    }

    /** Swings, decals (hover, target breathing, strike flash, coach), plates and ripples; voices panned. */
    private fun bellsFrame(state: ChimeUiState, now: Long) {
        val view = rack ?: return
        val pose = state.rack ?: return
        val swings = FloatArray(12) { i -> swingOf(i, now, state.freeze) }
        for (i in 0 until 12) view.swing(i, swings[i])

        // hover from the pointer ray (the system pointer never reports where it hovers)
        val h = if (state.hoverOff) null else hoverFromRay(pose, swings)
        if (h?.first != pointerHover || (h != null && h.second != pointerHoverLeft)) {
            if (h?.first != pointerHover) {
                ChimeLog.i("HOVER ${h?.first?.let { "B${it.bell + 1}:${it.zone.tag}" } ?: "none"}")
                sink(ChimeEvent.HoverChanged(h?.first))
            }
            pointerHover = h?.first
            pointerHoverLeft = h?.second ?: false
        }
        val hover = state.forcedHover ?: pointerHover
        val hoverLeft = if (state.forcedHover != null) false else pointerHoverLeft
        val target = state.target
        val breath = if (state.freeze) 0.8f else 0.55f + 0.25f * sin(2f * PI.toFloat() * (now % 1000L) / 1000f)
        val headLocal = if (hasHead) pose.toLocal(head.position) else null

        for (i in 0 until 12) {
            val spec = Bells.all[i]
            var zheng = 0f
            var right = 0f
            var left = 0f
            var plateL = 0f
            var plateR = 0f
            if (hover?.bell == i) {
                if (hover.zone == Zone.ZHENG) {
                    zheng = maxOf(zheng, HOVER_OPACITY)
                    plateL = maxOf(plateL, 1f)
                } else {
                    if (hoverLeft) left = maxOf(left, HOVER_OPACITY) else right = maxOf(right, HOVER_OPACITY)
                    plateR = maxOf(plateR, 1f)
                }
            }
            if (target?.bell == i) {
                if (target.zone == Zone.ZHENG) {
                    zheng = maxOf(zheng, breath)
                    plateL = maxOf(plateL, 1f)
                } else {
                    right = maxOf(right, breath)
                    left = maxOf(left, breath)
                    plateR = maxOf(plateR, 1f)
                }
            }
            state.demoStroke?.let { d -> if (d.bell == i) if (d.zone == Zone.ZHENG) plateL = 1f else plateR = 1f }
            val since = now - flashes[i]
            if (flashes[i] != NEVER && (since < FLASH_MS || (state.freeze && since < 60_000L))) {
                when (flashZone[i]) {
                    Zone.ZHENG -> zheng = 1f
                    Zone.CE -> if (flashLeft[i]) left = 1f else right = 1f
                    null -> Unit
                }
            }
            if (flashes[i] != NEVER && (since < PLATE_FLASH_MS || (state.freeze && since < 60_000L))) {
                if (flashZone[i] == Zone.ZHENG) plateL = 1f else plateR = 1f
            }
            if (i == COACH_BELL && coachStartMs != NEVER) {
                val t = (now - coachStartMs) / 1000f
                if (state.freeze) {
                    zheng = maxOf(zheng, 0.8f)
                    right = maxOf(right, 0.8f)
                } else if (t < COACH_BREATH_S) {
                    zheng = maxOf(zheng, 0.8f * sin(PI.toFloat() * t / COACH_BREATH_S))
                } else if (t < 2 * COACH_BREATH_S) {
                    right = maxOf(right, 0.8f * sin(PI.toFloat() * (t - COACH_BREATH_S) / COACH_BREATH_S))
                }
            }
            view.decal(i, DecalSlot.ZHENG, zheng)
            view.decal(i, DecalSlot.CE_RIGHT, right)
            view.decal(i, DecalSlot.CE_LEFT, left)
            // plates: shown with labels on, or for the hovered bell with labels off (design S15)
            val visible = state.labelsShown || hover?.bell == i
            view.plate(i, visible, RackView.plateCenter(spec, swings[i]), headLocal)
            view.plateGold(i, plateL * PLATE_GOLD, plateR * PLATE_GOLD)
        }

        // the done wave: silent ripples left to right
        if (pendingWave.isNotEmpty()) {
            val due = pendingWave.filter { it.first <= now }
            for ((_, i) in due) {
                val (p, n) = RackView.zonePoint(Bells.all[i], ce = false)
                ripples?.fire(i, p, n, now)
            }
            pendingWave.removeAll(due)
        }
        ripples?.frame(now, state.freeze) { swings[it] }
        audio?.pan { bell -> volumes(bell) }
    }

    /** After a language switch: one plate texture per frame (fast) or per second (idle emulator). */
    private fun redrawPlates(state: ChimeUiState, now: Long) {
        val view = rack ?: return
        if (!view.complete) return
        if (frameGapMs > FAST_FRAME_MS && now - lastPlateMs < SLOW_STEP_MS) return
        val i = view.platesToDraw(state.english).firstOrNull() ?: return
        val context = ChimeGraph.appContext ?: return
        lastPlateMs = now
        view.buildPlate(i, context.resources, state.english)
    }

    /** Damped swing about the beam axis: 2.5 / 4 degrees, period 1.2 s, gone in 1.5 s; strokes add up. */
    private fun swingOf(i: Int, now: Long, freeze: Boolean): Float {
        val list = impulses[i]
        if (list.isEmpty()) return 0f
        var a = 0f
        val it = list.iterator()
        while (it.hasNext()) {
            val (start, amp) = it.next()
            var t = (now - start) / 1000f
            if (freeze) t = t.coerceAtMost(0.3f)
            if (t > 1.6f) {
                it.remove()
                continue
            }
            a += -amp * exp(-t / SWING_TAU_S) * sin(2f * PI.toFloat() * t / SWING_PERIOD_S)
        }
        return a
    }

    /** The bell the pointer ray meets first (box around body and shank), its zone and whether it is the left side. */
    private fun hoverFromRay(pose: RackPose, swings: FloatArray): Pair<Hover, Boolean>? {
        if (!hasHead) return null
        val ray = pointerRay() ?: return null
        val o = pose.toLocal(ray.origin)
        val d = pose.rotation.conjugate().rotate(ray.direction)
        var best: Pair<Hover, Boolean>? = null
        var bestT = Float.MAX_VALUE
        for (spec in Bells.all) {
            val inv = Bells.lean(swings[spec.index]).conjugate()
            val lo = inv.rotate(o - Vec3(spec.xM, spec.hookY, 0f))
            val ld = inv.rotate(d)
            val half = Vec3(spec.widthM / 2f, spec.heightM / 2f, spec.depthM / 2f)
            val c = Vec3(0f, BellMeshes.boxCenterY(spec.heightM), 0f)
            val t = slab(lo - c, ld, half) ?: continue
            if (t < bestT) {
                bestT = t
                val hit = lo + ld * t
                best = Hover(spec.index, Bells.zoneAt(spec, hit.x)) to (hit.x < 0f)
            }
        }
        return best
    }

    /** Ray / axis-aligned box (centred at the origin) entry distance, or null. */
    private fun slab(o: Vec3, d: Vec3, h: Vec3): Float? {
        var t0 = 0f
        var t1 = 30f
        val os = floatArrayOf(o.x, o.y, o.z)
        val ds = floatArrayOf(d.x, d.y, d.z)
        val hs = floatArrayOf(h.x, h.y, h.z)
        for (k in 0..2) {
            if (abs(ds[k]) < 1e-6f) {
                if (abs(os[k]) > hs[k]) return null
            } else {
                var a = (-hs[k] - os[k]) / ds[k]
                var b = (hs[k] - os[k]) / ds[k]
                if (a > b) a = b.also { b = a }
                t0 = maxOf(t0, a)
                t1 = minOf(t1, b)
                if (t0 > t1) return null
            }
        }
        return t0
    }

    private fun pollPlanes(now: Long) {
        val count = planeTracker.count
        if (count == lastPlaneCount) return
        if (now - lastPlanesEmitMs < PLANES_EMIT_MS) return
        lastPlanesEmitMs = now
        lastPlaneCount = count
        val planes = if (ui.planesBlocked) emptyList() else planeTracker.all
        realFloorY = snapRule.floorY(planes)
        ceilingY = snapRule.ceilingY(planes)
        floorY = currentFloor(ui)
        framesKey = ""
        refreshFrames(ui)
        framesSent = frames.size
        val walls = planes.count { it.kind == SurfaceKind.WALL }
        ChimeLog.i("PLANES n=$count floor=${floorY?.let { ChimeLog.f(it) }} real=${realFloorY != null} ceiling=${ceilingY?.let { ChimeLog.f(it) }} walls=$walls frames=${frames.size}")
        sink(ChimeEvent.PlanesChanged(floorY, ceilingY, frames.size, virtual = realFloorY == null && floorY != null))
    }

    /** The detected floor, else the virtual one while the ViewModel asks for it (head height - 1.55 m + nudge). */
    private fun currentFloor(state: ChimeUiState): Float? {
        realFloorY?.let { return it }
        if (!state.virtualFloorOn && !state.floorVirtual) {
            virtualBaseY = null
            return null
        }
        val base = virtualBaseY ?: (if (hasHead && head.plausible) head.position.y - VIRTUAL_EYE_M else null)?.also { virtualBaseY = it } ?: return null
        return base + state.floorOffsetM
    }

    /** D7 (v0.1.2): stands the virtual floor in / moves it with the nudge / drops it when planes are blocked. */
    private fun syncFloor(state: ChimeUiState) {
        if (state.planesBlocked && realFloorY != null) {
            realFloorY = null
            lastPlaneCount = -1
        }
        // unblocked again: re-read the planes kept by the tracker so a real floor takes over at once
        if (!state.planesBlocked && planesWereBlocked) lastPlaneCount = -1
        planesWereBlocked = state.planesBlocked
        val want = currentFloor(state)
        if (want == floorY) return
        floorY = want
        framesKey = ""
        ChimeLog.i("FLOOR y=${want?.let { ChimeLog.f(it) }} virtual=${realFloorY == null && want != null}")
        sink(ChimeEvent.PlanesChanged(floorY, ceilingY, frames.size, virtual = realFloorY == null && want != null))
    }

    /** D14: walking into the locked rack makes it see-through. */
    private fun fadeFrame(state: ChimeUiState) {
        val view = rack ?: return
        val pose = state.rack ?: return
        val faded = hasHead && placeRule.clearance(pose, head.position) < FADE_NEAR_M
        if (faded != rackFaded) {
            rackFaded = faded
            view.setFaded(faded)
            ChimeLog.i("RACK faded=$faded")
        }
    }

    /** More menu "Bring here" (D14): the placed rack straight ahead of the head, facing the user. */
    fun bringRack() {
        val r = ui.rack ?: return
        if (!hasHead) return
        val pose = HeadFrame.rackAhead(head.position, head.rotation, r.center.y, r.scale)
        ChimeLog.i("BRING center=${ChimeLog.pos(pose.center)}")
        sink(ChimeEvent.RackMoved(pose))
    }

    private fun pollTracking(now: Long) {
        val lost = headTracker.lost(now)
        if (lost == lastLost) return
        lastLost = lost
        sink(ChimeEvent.TrackingChanged(lost))
    }

    // =========================================================================================
    // system pointer (gaze + pinch, emulator mouse)
    // =========================================================================================

    /** A system spatial tap on one of our entities. */
    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        if (target == null) {
            ChimeLog.i("TAP other target=none")
            return
        }
        val bell = rack?.bellOf(target)
        val planeId = colliders?.planeIdOf(target)
        when {
            bell != null -> {
                val spec = Bells.all[bell]
                val local = stage?.let { s -> localOnBell(bell, s) }
                val zone = if (local != null) Bells.zoneAt(spec, local.x) else Zone.ZHENG
                ChimeLog.i("TAP bell=${spec.id} zone=${zone.tag} local=${ChimeLog.pos(local)}")
                sink(ChimeEvent.BellTapped(bell, zone, local))
            }
            planeId != null || colliders?.isBackstop(target) == true -> {
                val floorTap = planeId != null && planeTracker.get(planeId)?.kind == SurfaceKind.FLOOR
                ChimeLog.i("TAP surface=$name floor=$floorTap stage=${ChimeLog.pos(stage)}")
                if (ui.room == Room.PLACING) sink(ChimeEvent.PlaceRequested(if (floorTap && stage != null) tapPose(stage) else placeablePose()))
                else sink(ChimeEvent.NothingTapped)
            }
            else -> {
                ChimeLog.i("TAP other target=$name stage=${ChimeLog.pos(stage)}")
                sink(ChimeEvent.NothingTapped)
            }
        }
    }

    /** A floor tap while placing: the footprint centre goes to the tap point (the held snap frame if it is near). */
    private fun tapPose(stage: Vec3): RackPose? {
        val fy = floorY ?: return placeablePose()
        if (!hasHead) return placeablePose()
        // a floor tap behind the user places the ghost (which is in front) instead
        if (!HeadFrame.inFront(head.position, head.rotation, stage)) return placeablePose()
        val (p, near) = placeRule.clamp(Vec3(stage.x, fy, stage.z), head.position, fy)
        if (near || ui.forcedNear) return null
        val scale = ui.scale
        held?.let { f -> if (Vec3(p.x - f.center.x, 0f, p.z - f.center.z).length < PlacementUseCase.RELEASE_M * maxOf(scale, 0.5f)) return RackPose(f.center, f.yaw, scale) }
        val yaw = if (userTurned) ghostYaw else placeRule.yawFacing(p, head.position, ghostYaw)
        val snap = placeRule.snap(p, yaw, frames, null, scale)
        val pose = if (snap != null) RackPose(snap.center, snap.yaw, scale) else RackPose(p, yaw, scale)
        // D14: a tap whose footprint would come within 0.8 m of the head is refused (the ghost turns red there)
        return if (placeRule.pushClear(pose, head.position).second) null else pose
    }

    /** A Stage point -> the bell's own coordinates (origin at the hanging point, unswung lean). */
    private fun localOnBell(bell: Int, stage: Vec3): Vec3? {
        val pose = ui.rack ?: return null
        val spec = Bells.all[bell]
        val rackLocal = pose.toLocal(stage)
        return Bells.lean(swingOf(bell, SystemClock.elapsedRealtime(), ui.freeze)).conjugate().rotate(rackLocal - Vec3(spec.xM, spec.hookY, 0f))
    }

    /** View-local pixels of a spatial tap -> Stage metres, through the SpatialView content conversion. */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .onFailure { ChimeLog.w("TAP convert failed: $it") }
            .getOrNull()
    }

    /** Debug `place=auto`: 2.5 m ahead on the floor, facing the viewer (floor guessed 1.55 m below the head when unknown). */
    private fun autoPose(): RackPose? {
        if (!hasHead) return null
        val fy = floorY ?: (head.position.y - 1.55f)
        return HeadFrame.rackAhead(head.position, head.rotation, fy, ui.scale, AUTO_DISTANCE_M)
    }

    /**
     * A recenter (pose jump, or the check after coming back to the front): panels re-park in front of the
     * head and the placing ghost starts ahead again. A placed rack is locked (v0.1.3): when it is more than
     * [offRad] off the level heading it stays put and the ViewModel shows the "use Move" snackbar.
     */
    private fun onRecentered(reason: String, offRad: Float): String {
        follower.reset()
        if (ui.dialog != null) dialogPlaced = false
        val room = ui.room
        val rack = ui.rack
        val off = rack?.let { HeadFrame.bearing(head.position, head.rotation, it.center) }
        val yawDeg = Math.toDegrees(HeadFrame.yawOf(HeadFrame.heading(head.rotation)).toDouble()).toFloat()
        val line =
            "RECENTER reason=$reason room=$room head=${ChimeLog.pos(head.position)} yaw=${ChimeLog.f(yawDeg, 1)}" +
                (off?.let { " rackOff=${ChimeLog.f(Math.toDegrees(it.toDouble()).toFloat(), 1)}" } ?: "")
        return when {
            room == Room.PLACING -> {
                ghostCenter = null
                "$line ghost=ahead"
            }
            room.placed && rack != null && off != null && off > offRad -> {
                val fy = floorY ?: rack.center.y
                val pose = HeadFrame.rackAhead(head.position, head.rotation, fy, rack.scale)
                sink(ChimeEvent.RackRecentered(pose))
                "$line rack=locked-off ahead=${ChimeLog.pos(pose.center)}"
            }
            else -> "$line rack=kept"
        }.also { ChimeLog.i(it) }
    }

    // =========================================================================================
    // debug probe
    // =========================================================================================

    override fun poseLine(): String =
        "POSE hmd pos=${ChimeLog.pos5(head.position)} ${ChimeLog.quat5(head.rotation)} fwd=${ChimeLog.pos5(head.forward)} valid=$hasHead plausible=${head.plausible}" +
            (ui.rack?.let { " rackClear=${ChimeLog.f(placeRule.clearance(it, head.position))}" } ?: "")

    override fun panelLines(): List<String> {
        val rig = rig ?: return emptyList()
        return rig.all.filter { it.shown && it.panel != null }.map { slot ->
            String.format(Locale.US, "PANEL id=%s pos=%s %s scale=%.3f size_dp=%.0fx%.0f engine=%s", slot.id, ChimeLog.pos5(slot.position), ChimeLog.quat5(slot.rotation), slot.scale, slot.widthDp, slot.heightDp, ChimeLog.pos5(slot.enginePosition()))
        }
    }

    override fun planesSummary(): List<String> =
        planeTracker.all.map { p -> "PLANE ${p.label} kind=${p.kind} center=${ChimeLog.pos(p.centroid)} normal=${ChimeLog.pos(p.normal)} verts=${p.vertices.size} top=${ChimeLog.f(p.maxY)}" } +
            frames.mapIndexed { k, f -> "FRAME $k center=${ChimeLog.pos(f.center)} yaw=${ChimeLog.f(Math.toDegrees(f.yaw.toDouble()).toFloat(), 1)} wall=${ChimeLog.f(f.wallLength)}" }

    override fun recenter(): String = if (hasHead) onRecentered("debug", 0f) else "RECENTER ignored: no head"

    override fun parkPanel(): String {
        follower.park(head, planeTracker.all, SystemClock.elapsedRealtime())
        return "FOLLOW control park requested"
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val FRAME_MS = 20L
        private const val FAST_FRAME_MS = 40L
        private const val SLOW_STEP_MS = 600L
        private const val REBIND_DELAY_MS = 150L
        private const val PLANES_EMIT_MS = 500L
        private const val PANEL_HOLD_MS = 600L
        private const val FOLLOW_TAU_S = 0.12f
        private const val SNAP_TAU_S = 0.05f
        private const val STICK_DEAD = 0.15f
        private const val STICK_RATE_RAD_S = 1.5708f
        private const val STICK_TAU_S = 0.15f
        private const val TURN_MS = 250f
        private const val AUTO_DISTANCE_M = HeadFrame.RACK_AHEAD_M
        private const val FRONT_CHECK_DELAY_MS = 1500L
        private const val FRONT_CHECK_GIVE_UP_MS = 10_000L

        /** Virtual floor: this far below the eyes (QA r2 suggestion; `place=auto` already used it). */
        private const val VIRTUAL_EYE_M = 1.55f

        /** The rack turns see-through when the head comes within this of its footprint (D14). */
        private const val FADE_NEAR_M = 0.3f
        private val SWING_LOWER_RAD = Math.toRadians(2.5).toFloat()
        private val SWING_UPPER_RAD = Math.toRadians(4.0).toFloat()
        private const val SWING_PERIOD_S = 1.2f
        private const val SWING_TAU_S = 0.35f
        private const val HOVER_OPACITY = 0.6f
        private const val FLASH_MS = 120L
        private const val PLATE_FLASH_MS = 300L
        private const val PLATE_GOLD = 0.55f
        private const val WAVE_STEP_MS = 100L
        private const val COACH_BELL = 5
        private const val COACH_BREATH_S = 1.2f
        private val PAN_RANGE_RAD = Math.toRadians(60.0).toFloat()

        /** Half sizes of the control panel at 0.75 with a little air, for the surface rule. */
        private val MAIN_HALF_EXTENTS = listOf(0.13f, 0.09f, 0.01f)

        /** 14 degrees above the view centre, 0.9 m away: (0, sin14, -cos14) * 0.9. */
        private val SNACK_HEAD_OFFSET = Vec3(0f, 0.2177f, -0.8733f)
    }
}
