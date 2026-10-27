package io.embodit.bianzhong.ui.chime

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.bianzhong.domain.model.Song
import io.embodit.bianzhong.domain.model.Vec3
import io.embodit.bianzhong.domain.model.Zone
import io.embodit.bianzhong.domain.usecase.RackPose
import io.embodit.bianzhong.domain.usecase.WallSnapUseCase
import io.embodit.bianzhong.platform.ChimeGraph
import io.embodit.bianzhong.platform.ChimeLog
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The whole state machine of the app (design `states`, `stateModel`, `interactions`). Events come from
 * the panels, the scene (planes, ghost, taps, hover) and the debug hooks; the state goes out as
 * [ChimeUiState], strokes and other one-shots as [ChimeUiEffect]. Main thread only.
 *
 * Nothing about the room is saved (design `offline`): every start places the rack again and lands in
 * free play. Four flags are kept: language, plate labels, size, and whether the coach was seen.
 */
class ChimeViewModel(private val appContext: Context) : ViewModel() {

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChimeViewModel(context.applicationContext) as T
    }

    private val prefs = appContext.getSharedPreferences("bianzhong", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(ChimeUiState())
    val state: StateFlow<ChimeUiState> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<ChimeUiEffect>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val effects = _effects.asSharedFlow()

    // ---- the model
    private var room = Room.STARTING
    private var rack: RackPose? = null
    private var scale = 1f
    private var song = Song.MOLIHUA
    private var step = 0
    private var labelsShown = true
    private var language = LanguagePref.SYSTEM
    private var coachSeen = false

    // ---- session facts
    private var sceneReady = false
    private var floorY: Float? = null
    private var ceilingY: Float? = null
    private var frames = 0
    private var menuOpen = false
    private var dialog: DialogKind? = null
    private var transientSnack: SnackUi? = null
    private var snackSerial = 0
    private var snackJob: Job? = null
    private var snapped = false
    private var tooClose = false
    private var hover: Hover? = null
    private var forcedHover: Hover? = null
    private var hoverOff = false
    private var coach = false
    private var coachJob: Job? = null
    private var freeze = false
    private var turnSerial = 0
    private var forcedNear = false
    private var forceWall = false
    private var wallMinOverride: Float? = null
    private var ceilingOverride: Float? = null
    private var floorTimeoutS = 60
    private var planesOn = true
    private var background = false
    private var demoStroke: io.embodit.bianzhong.domain.model.Stroke? = null
    private var struckUntilMs = 0L
    private var placingSinceMs = NEVER
    private var noWall = false
    private var ceilingWarned = false
    private var forcedNoWall = false
    private var demoJob: Job? = null
    private var findJob: Job? = null
    private var placingJob: Job? = null
    private var planesOffJob: Job? = null
    private var lastStateLine = ""

    init {
        labelsShown = prefs.getBoolean(KEY_LABELS, true)
        language = LanguagePref.fromTag(prefs.getString(KEY_LANGUAGE, "system"))
        scale = if (prefs.getInt(KEY_SCALE, 1) == 2) 0.5f else 1f
        coachSeen = prefs.getBoolean(KEY_COACH, false)
        publish()
    }

    fun onEvent(event: ChimeEvent) {
        when (event) {
            ChimeEvent.SceneReady -> {
                sceneReady = true
                if (room == Room.STARTING) enterFinding()
            }
            is ChimeEvent.PlanesChanged -> {
                floorY = event.floorY
                ceilingY = event.ceilingY
                frames = event.frames
                if (event.floorY != null && (room == Room.FINDING || room == Room.STARTING)) enterPlacing()
                updateNoWall()
            }
            ChimeEvent.PlaneTrackingFailed -> {
                if (!room.placed) {
                    room = Room.BLOCKED
                    dialog = DialogKind.BLOCKED
                }
            }
            is ChimeEvent.GhostChanged -> {
                if (room != Room.PLACING) return
                if (event.snapped && !snapped) showSnack(SnackKind.SNAPPED, 800L)
                snapped = event.snapped
                tooClose = event.tooClose
            }
            is ChimeEvent.PlaceRequested -> {
                if (menuOpen) {
                    menuOpen = false
                } else if (room == Room.PLACING && dialog == null) {
                    val pose = event.pose
                    if (pose == null) {
                        ChimeLog.i("PLACE refused (too close or no floor)")
                    } else {
                        place(pose)
                    }
                }
            }
            is ChimeEvent.RackRecentered -> {
                if (room.placed && rack != null) {
                    rack = event.pose.copy(scale = scale)
                    ChimeLog.i("PLACE recentred center=${ChimeLog.pos(event.pose.center)} yaw=${ChimeLog.f(Math.toDegrees(event.pose.yaw.toDouble()).toFloat(), 1)}")
                }
            }
            is ChimeEvent.BellTapped -> onBell(event.bell, event.zone, event.local)
            ChimeEvent.NothingTapped -> if (menuOpen) menuOpen = false
            is ChimeEvent.HoverChanged -> hover = event.hover
            is ChimeEvent.TrackingChanged -> ChimeLog.i("TRACK head lost=${event.lost}")

            is ChimeEvent.ModeSelected -> {
                if (!room.placed) return
                stopDemo()
                if (event.follow) {
                    if (!room.follow) {
                        room = Room.FOLLOW_PICK
                        step = 0
                    }
                } else {
                    room = Room.FREE
                    step = 0
                }
            }
            is ChimeEvent.SongSelected -> {
                if (!room.follow) return
                stopDemo()
                song = event.song
                room = Room.FOLLOW_PICK
                step = 0
            }
            ChimeEvent.ListenClicked -> if (room.follow) startDemo(0)
            ChimeEvent.FollowClicked, ChimeEvent.AgainClicked -> if (room.follow) startFollow(0)
            ChimeEvent.StopClicked -> {
                stopDemo()
                if (room == Room.DEMO || room == Room.FOLLOW) {
                    room = Room.FOLLOW_PICK
                    step = 0
                }
            }
            ChimeEvent.MoreClicked -> {
                if (room == Room.STARTING || room == Room.FINDING || dialog != null) return
                menuOpen = !menuOpen
            }
            ChimeEvent.Turn45Clicked -> if (room == Room.PLACING) turnSerial++
            is ChimeEvent.LabelsSelected -> {
                labelsShown = event.shown
                prefs.edit().putBoolean(KEY_LABELS, labelsShown).apply()
            }
            is ChimeEvent.LanguageSelected -> {
                language = event.language
                prefs.edit().putString(KEY_LANGUAGE, language.tag).apply()
            }
            is ChimeEvent.ScaleSelected -> {
                scale = if (event.scale < 0.75f) 0.5f else 1f
                prefs.edit().putInt(KEY_SCALE, if (scale < 1f) 2 else 1).apply()
                rack = rack?.copy(scale = scale)
                snapped = false
                updateNoWall()
            }
            ChimeEvent.ReplaceClicked -> {
                menuOpen = false
                if (room.placed || room == Room.PLACING) replace()
            }
            ChimeEvent.ExitClicked -> exit()
            ChimeEvent.RetryClicked -> {
                when (dialog) {
                    DialogKind.NO_FLOOR -> {
                        dialog = null
                        enterFinding()
                    }
                    DialogKind.BLOCKED -> {
                        dialog = null
                        room = Room.STARTING
                        enterFinding()
                    }
                    null -> Unit
                }
            }
            ChimeEvent.WentBackground -> {
                background = true
                stopDemo()
                if (room == Room.DEMO) {
                    room = Room.FOLLOW_PICK
                    step = 0
                }
                emit(ChimeUiEffect.StopSounds)
                ChimeLog.i("LIFECYCLE background room=$room")
            }
            ChimeEvent.CameForeground -> {
                if (!background) return
                background = false
                if (!room.placed && room != Room.NO_FLOOR && room != Room.BLOCKED) {
                    planesOn = true
                    if (room == Room.STARTING || room == Room.FINDING) enterFinding()
                }
                ChimeLog.i("LIFECYCLE foreground room=$room")
            }
            is ChimeEvent.Debug -> debug(event.key, event.value)
        }
        publish()
    }

    // =========================================================================================
    // rules
    // =========================================================================================

    private fun enterFinding() {
        room = Room.FINDING
        planesOn = true
        findJob?.cancel()
        if (floorY != null) {
            enterPlacing()
            return
        }
        val timeout = floorTimeoutS
        findJob =
            viewModelScope.launch {
                delay(timeout * 1000L)
                if (room == Room.FINDING && floorY == null) {
                    room = Room.NO_FLOOR
                    dialog = DialogKind.NO_FLOOR
                    ChimeLog.i("FLOOR timeout after ${timeout}s")
                    publish()
                }
            }
    }

    private fun enterPlacing() {
        findJob?.cancel()
        room = Room.PLACING
        planesOn = true
        planesOffJob?.cancel()
        snapped = false
        tooClose = false
        placingSinceMs = SystemClock.elapsedRealtime()
        // the low ceiling hint, once (design I23)
        val ceiling = ceilingOverride ?: ceilingY?.let { c -> floorY?.let { f -> c - f } }
        if (!ceilingWarned && ceiling != null && ceiling < LOW_CEILING_M) {
            ceilingWarned = true
            showSnack(SnackKind.LOW_CEILING, 5000L)
        }
        placingJob?.cancel()
        placingJob =
            viewModelScope.launch {
                delay(NO_WALL_AFTER_MS)
                updateNoWall()
                publish()
            }
    }

    private fun updateNoWall() {
        val waited = placingSinceMs != NEVER && SystemClock.elapsedRealtime() - placingSinceMs >= NO_WALL_AFTER_MS
        noWall = room == Room.PLACING && !forceWall && (forcedNoWall || (frames == 0 && waited))
    }

    private fun place(pose: RackPose) {
        rack = pose.copy(scale = scale)
        room = Room.FREE
        step = 0
        snapped = false
        tooClose = false
        noWall = false
        forceWall = false
        forcedNear = false
        ChimeLog.i("PLACE center=${ChimeLog.pos(pose.center)} yaw=${ChimeLog.f(Math.toDegrees(pose.yaw.toDouble()).toFloat(), 1)} scale=$scale")
        emit(ChimeUiEffect.Click)
        // stop plane detection 15 s after the rack is placed (design container.lifecycle)
        planesOffJob?.cancel()
        planesOffJob =
            viewModelScope.launch {
                delay(PLANES_OFF_MS)
                if (room.placed) {
                    planesOn = false
                    publish()
                }
            }
        if (!coachSeen) startCoach()
    }

    private fun replace() {
        stopDemo()
        step = 0
        rack = null
        hover = null
        enterPlacing()
    }

    private fun startCoach() {
        coachSeen = true
        prefs.edit().putBoolean(KEY_COACH, true).apply()
        coach = true
        showSnack(SnackKind.COACH, COACH_MS)
        coachJob?.cancel()
        if (freeze) return
        coachJob =
            viewModelScope.launch {
                delay(COACH_MS)
                coach = false
                publish()
            }
    }

    private fun endCoach() {
        if (!coach) return
        coach = false
        coachJob?.cancel()
        if (transientSnack?.kind == SnackKind.COACH) transientSnack = null
    }

    private fun onBell(bell: Int, zone: Zone, local: Vec3?) {
        if (menuOpen) {
            // the first tap anywhere only closes the menu (design I20)
            menuOpen = false
            return
        }
        if (!room.placed || dialog != null) return
        endCoach()
        strike(bell, zone, local, user = true)
        if (room == Room.FOLLOW) {
            val target = song.strokes.getOrNull(step)
            if (target != null && target.bell == bell && target.zone == zone) {
                step++
                ChimeLog.i("FOLLOW hit step=$step/${song.strokes.size}")
                if (step >= song.strokes.size) {
                    room = Room.DONE
                    emit(ChimeUiEffect.Wave)
                    ChimeLog.i("FOLLOW done song=${song.tag}")
                }
            } else {
                ChimeLog.i("FOLLOW miss want=${target?.let { "B${it.bell + 1},${it.zone.tag}" }} got=B${bell + 1},${zone.tag}")
            }
        }
    }

    private fun strike(bell: Int, zone: Zone, local: Vec3?, user: Boolean) {
        struckUntilMs = SystemClock.elapsedRealtime() + STRUCK_MS
        emit(ChimeUiEffect.Strike(bell, zone, local, user))
    }

    private fun startFollow(at: Int) {
        stopDemo()
        room = Room.FOLLOW
        step = at.coerceIn(0, song.strokes.size - 1)
    }

    private fun startDemo(from: Int) {
        stopDemo()
        room = Room.DEMO
        step = from.coerceIn(0, song.strokes.size)
        if (freeze) {
            demoStroke = song.strokes.getOrNull(maxOf(step - 1, 0))
            return
        }
        val strokes = song.strokes
        val beat = song.beatMs
        demoJob =
            viewModelScope.launch {
                while (step < strokes.size) {
                    val s = strokes[step]
                    demoStroke = s
                    strike(s.bell, s.zone, null, user = false)
                    step++
                    publish()
                    delay(beat)
                }
                delay(beat)
                demoStroke = null
                if (room == Room.DEMO) {
                    room = Room.FOLLOW_PICK
                    step = 0
                }
                publish()
            }
    }

    private fun stopDemo() {
        demoJob?.cancel()
        demoJob = null
        demoStroke = null
    }

    private fun exit() {
        stopDemo()
        emit(ChimeUiEffect.StopSounds)
        emit(ChimeUiEffect.ExitApp)
    }

    private fun showSnack(kind: SnackKind, ms: Long) {
        val s = SnackUi(++snackSerial, kind)
        transientSnack = s
        snackJob?.cancel()
        ChimeLog.i("SNACK ${kind.name.lowercase(Locale.US)}")
        if (freeze) return
        snackJob =
            viewModelScope.launch {
                delay(ms)
                if (transientSnack?.id == s.id) {
                    transientSnack = null
                    publish()
                }
            }
    }

    private fun emit(effect: ChimeUiEffect) {
        _effects.tryEmit(effect)
    }

    // =========================================================================================
    // state out
    // =========================================================================================

    private fun englishNow(): Boolean =
        when (language) {
            LanguagePref.EN -> true
            LanguagePref.ZH -> false
            LanguagePref.SYSTEM -> {
                val locales = appContext.resources.configuration.locales
                val lang = if (locales.isEmpty) "en" else locales[0].language
                lang != "zh"
            }
        }

    private fun wallMin(): Float = wallMinOverride ?: if (scale < 1f) WallSnapUseCase.HALF_MIN_WALL_M else WallSnapUseCase.DEFAULT_MIN_WALL_M

    private fun publish() {
        val snack =
            transientSnack
                ?: if (noWall && room == Room.PLACING) SnackUi(-1, SnackKind.NO_WALL) else null
        val target = if (room == Room.FOLLOW) song.strokes.getOrNull(step) else null
        _state.value =
            ChimeUiState(
                room = room,
                rack = rack,
                scale = scale,
                song = song,
                step = step,
                menuOpen = menuOpen,
                dialog = dialog,
                snack = snack,
                labelsShown = labelsShown,
                language = language,
                english = englishNow(),
                snapped = snapped,
                tooClose = tooClose || (forcedNear && room == Room.PLACING),
                noWall = noWall,
                hover = forcedHover ?: hover,
                forcedHover = forcedHover,
                hoverOff = hoverOff,
                coach = coach,
                freeze = freeze,
                turnSerial = turnSerial,
                forcedNear = forcedNear,
                forceWall = forceWall,
                wallMin = if (forcedNoWall) 99f else wallMin(),
                planesOn = planesOn,
                background = background,
                target = target,
                demoStroke = if (room == Room.DEMO) demoStroke else null,
                struckUntilMs = struckUntilMs,
                floorKnown = floorY != null,
            )
        val line = stateLine()
        if (line != lastStateLine) {
            lastStateLine = line
            ChimeLog.i(line)
        }
    }

    /** Design state names: the room state plus the overlay marks (S4 S5 S10 S12 S15 S16). */
    fun stateName(): String {
        dialog?.let { return if (it == DialogKind.NO_FLOOR) "S13" else "S14" }
        return when (room) {
            Room.STARTING -> "S0"
            Room.FINDING -> "S1"
            Room.PLACING ->
                when {
                    tooClose || forcedNear -> "S2-near"
                    snapped -> "S2a"
                    noWall -> "S17"
                    else -> "S2"
                }
            Room.FREE -> "S3"
            Room.FOLLOW_PICK -> "S6"
            Room.DEMO -> "S7"
            Room.FOLLOW -> "S8"
            Room.DONE -> "S9"
            Room.NO_FLOOR -> "S13"
            Room.BLOCKED -> "S14"
        }
    }

    private fun overlays(): List<String> {
        val out = ArrayList<String>()
        if (room.placed) {
            if ((forcedHover ?: hover) != null) out += "S4"
            if (SystemClock.elapsedRealtime() < struckUntilMs) out += "S5"
            if (!labelsShown) out += "S15"
        }
        if (menuOpen) out += "S10"
        if (scale < 1f) out += "S12"
        if (englishNow()) out += "S16"
        return out
    }

    private fun stateLine(): String {
        val h = forcedHover ?: hover
        return "STATE ${stateName()} overlays=${overlays().joinToString(",").ifEmpty { "-" }} song=${song.tag} step=$step/${song.strokes.size}" +
            " hover=${h?.let { "B${it.bell + 1}:${it.zone.tag}" } ?: "-"} coach=$coach freeze=$freeze"
    }

    fun dumpJson(): String {
        val s = _state.value
        val o = JSONObject()
        o.put("state", stateName())
        o.put("overlays", overlays().joinToString(","))
        o.put("room", room.name)
        o.put("song", song.tag)
        o.put("step", step)
        o.put("strokes", song.strokes.size)
        o.put("target", s.target?.let { "B${it.bell + 1},${it.zone.tag},${it.note}" } ?: JSONObject.NULL)
        o.put("scale", scale.toDouble())
        o.put("labels", labelsShown)
        o.put("language", language.tag)
        o.put("english", s.english)
        o.put("menu", menuOpen)
        o.put("dialog", dialog?.name ?: JSONObject.NULL)
        o.put("snack", s.snack?.kind?.name ?: JSONObject.NULL)
        o.put("snapped", snapped)
        o.put("tooClose", s.tooClose)
        o.put("noWall", noWall)
        o.put("floorY", floorY?.toDouble() ?: JSONObject.NULL)
        o.put("ceilingY", ceilingY?.toDouble() ?: JSONObject.NULL)
        o.put("frames", frames)
        o.put("planesOn", planesOn)
        o.put("coachSeen", coachSeen)
        rack?.let { r ->
            o.put("rack", JSONObject().put("center", ChimeLog.pos(r.center)).put("yawDeg", Math.toDegrees(r.yaw.toDouble())).put("scale", r.scale.toDouble()))
        }
        return o.toString()
    }

    // =========================================================================================
    // debug hooks (design `debugHooks`)
    // =========================================================================================

    private fun debug(key: String, value: String) {
        val v = value.lowercase(Locale.US)
        ChimeLog.i("CMD $key=$value")
        when (key) {
            "state", "goto" -> debugState(value.uppercase(Locale.US))
            "place" -> {
                when (v) {
                    "auto" -> requestPlace(auto = true)
                    "wall" -> {
                        if (room != Room.PLACING) replace()
                        forceWall = true
                        noWall = false
                    }
                }
            }
            "near" -> {
                if (room != Room.PLACING && room.placed) replace()
                forcedNear = v == "1" || v == "on"
            }
            "floor_timeout" -> floorTimeoutS = v.toIntOrNull()?.coerceIn(1, 600) ?: 60
            "wallmin" -> {
                val m = v.toFloatOrNull()
                forcedNoWall = m != null && m >= 99f
                wallMinOverride = if (m == null || m >= 99f) null else m
                updateNoWall()
            }
            "strike" -> {
                val parts = v.split(',', ' ')
                val n = parts.getOrNull(0)?.removePrefix("b")?.toIntOrNull() ?: return
                val zone = Zone.fromTag(parts.getOrNull(1)) ?: Zone.ZHENG
                if (n in 1..12) onBell(n - 1, zone, null)
            }
            "hover" -> {
                hoverOff = v == "off"
                if (v == "0" || v == "off" || v.isEmpty()) {
                    forcedHover = null
                } else {
                    val parts = v.split(',', ' ')
                    val n = parts.getOrNull(0)?.removePrefix("b")?.toIntOrNull()
                    val zone = Zone.fromTag(parts.getOrNull(1)) ?: Zone.ZHENG
                    forcedHover = if (n != null && n in 1..12) Hover(n - 1, zone) else null
                }
            }
            "mode" -> {
                if (!room.placed) requestPlace(auto = true)
                when (v) {
                    "free" -> onEvent(ChimeEvent.ModeSelected(false))
                    "follow" -> {
                        if (!room.follow) room = Room.FOLLOW_PICK
                        startFollow(0)
                    }
                    "pick" -> {
                        stopDemo()
                        room = Room.FOLLOW_PICK
                        step = 0
                    }
                    "demo" -> startDemo(0)
                }
            }
            "song" -> {
                val s = Song.fromTag(v) ?: return
                val wasRoom = room
                song = s
                when (wasRoom) {
                    Room.FOLLOW -> startFollow(0)
                    Room.DEMO -> startDemo(0)
                    Room.DONE -> step = song.strokes.size
                    else -> step = 0
                }
            }
            "step" -> {
                val n = v.toIntOrNull() ?: return
                when (room) {
                    Room.FOLLOW -> step = (n - 1).coerceIn(0, song.strokes.size - 1)
                    Room.DEMO -> {
                        stopDemo()
                        step = n.coerceIn(0, song.strokes.size)
                        demoStroke = song.strokes.getOrNull(maxOf(step - 1, 0))
                        if (!freeze) startDemo(step)
                    }
                    else -> step = n.coerceIn(0, song.strokes.size)
                }
            }
            "done" -> {
                if (!room.placed) requestPlace(auto = true)
                stopDemo()
                room = Room.DONE
                step = song.strokes.size
                emit(ChimeUiEffect.Wave)
            }
            "scale" -> onEvent(ChimeEvent.ScaleSelected(if (v == "2" || v == "0.5") 0.5f else 1f))
            "lang" -> onEvent(ChimeEvent.LanguageSelected(LanguagePref.fromTag(v)))
            "labels" -> onEvent(ChimeEvent.LabelsSelected(v != "0" && v != "off"))
            "ceiling" -> {
                ceilingOverride = v.toFloatOrNull()
                ceilingWarned = false
                if (room == Room.PLACING) {
                    val c = ceilingOverride
                    if (c != null && c < LOW_CEILING_M) {
                        ceilingWarned = true
                        showSnack(SnackKind.LOW_CEILING, 5000L)
                    }
                }
            }
            "coach" -> {
                if (!room.placed) requestPlace(auto = true)
                startCoach()
            }
            "coach_reset" -> {
                coachSeen = false
                prefs.edit().putBoolean(KEY_COACH, false).apply()
            }
            "freeze" -> {
                freeze = v == "1" || v == "on"
                if (freeze) {
                    demoJob?.cancel()
                    demoJob = null
                    if (room == Room.DEMO) demoStroke = song.strokes.getOrNull(maxOf(step - 1, 0))
                    snackJob?.cancel()
                    coachJob?.cancel()
                } else {
                    // let a snackbar / coach held by the freeze go 3 s after it lifts
                    transientSnack?.let { s ->
                        snackJob?.cancel()
                        snackJob =
                            viewModelScope.launch {
                                delay(UNFREEZE_LINGER_MS)
                                if (transientSnack?.id == s.id) {
                                    transientSnack = null
                                    publish()
                                }
                            }
                    }
                    if (coach) {
                        coachJob?.cancel()
                        coachJob =
                            viewModelScope.launch {
                                delay(UNFREEZE_LINGER_MS)
                                coach = false
                                publish()
                            }
                    }
                }
            }
            "menu" -> {
                if (room.placed || room == Room.PLACING) menuOpen = v != "0" && v != "off"
            }
            "stop" -> onEvent(ChimeEvent.StopClicked)
            "replace" -> onEvent(ChimeEvent.ReplaceClicked)
            "exit" -> exit()
            "retry" -> onEvent(ChimeEvent.RetryClicked)
            else -> ChimeLog.i("CMD unknown key '$key'")
        }
    }

    /** Debug `place=auto`: the scene places the rack 2.5 m ahead facing the viewer (design `debugHooks`). */
    private fun requestPlace(auto: Boolean) {
        val pose = ChimeGraph.autoPlacement?.invoke(auto) ?: return ChimeLog.i("CMD place: no head / floor yet")
        if (room != Room.PLACING) {
            findJob?.cancel()
            room = Room.PLACING
        }
        dialog = null
        place(pose)
    }

    private fun debugState(name: String) {
        stopDemo()
        when (name) {
            "S0" -> {
                rack = null
                room = Room.STARTING
                findJob?.cancel()
            }
            "S1" -> {
                rack = null
                room = Room.FINDING
                findJob?.cancel()
            }
            "S2" -> {
                replace()
                forceWall = false
                forcedNear = false
                forcedNoWall = false
            }
            "S2A" -> {
                replace()
                forceWall = true
            }
            "S3" -> {
                if (!room.placed) requestPlace(true)
                room = Room.FREE
            }
            "S6" -> {
                if (!room.placed) requestPlace(true)
                room = Room.FOLLOW_PICK
                step = 0
            }
            "S13" -> {
                rack = null
                room = Room.NO_FLOOR
                dialog = DialogKind.NO_FLOOR
            }
            "S14" -> {
                rack = null
                room = Room.BLOCKED
                dialog = DialogKind.BLOCKED
            }
            "S17" -> {
                replace()
                forceWall = false
                forcedNoWall = true
                noWall = true
            }
            else -> ChimeLog.i("CMD state $name: use the design combos (mode=, strike=, done=1 ...)")
        }
        if (name != "S13" && name != "S14") dialog = null
        menuOpen = false
    }

    override fun onCleared() {
        stopDemo()
        super.onCleared()
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val KEY_LABELS = "labels"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_SCALE = "scale"
        private const val KEY_COACH = "coachSeen"
        private const val LOW_CEILING_M = 2.30f
        private const val NO_WALL_AFTER_MS = 8000L
        private const val PLANES_OFF_MS = 15_000L
        private const val COACH_MS = 6000L
        private const val STRUCK_MS = 1500L
        private const val UNFREEZE_LINGER_MS = 3000L
    }
}
