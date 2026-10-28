package io.embodit.bianzhong.ui.chime

import io.embodit.bianzhong.domain.model.Song
import io.embodit.bianzhong.domain.model.Stroke
import io.embodit.bianzhong.domain.model.Vec3
import io.embodit.bianzhong.domain.model.Zone
import io.embodit.bianzhong.domain.usecase.RackPose

/** The room state (design `stateModel.rule`, first layer). */
enum class Room {
    STARTING, // S0
    FINDING, // S1
    PLACING, // S2 / S2a / S17
    FREE, // S3
    FOLLOW_PICK, // S6
    DEMO, // S7
    FOLLOW, // S8
    DONE, // S9
    NO_FLOOR, // S13
    BLOCKED; // S14

    /** The rack hangs in the room. */
    val placed: Boolean
        get() = this == FREE || this == FOLLOW_PICK || this == DEMO || this == FOLLOW || this == DONE

    /** The follow panel (the tall control panel) is showing. */
    val follow: Boolean
        get() = this == FOLLOW_PICK || this == DEMO || this == FOLLOW || this == DONE
}

enum class LanguagePref(val tag: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en");

    companion object {
        fun fromTag(tag: String?): LanguagePref = entries.firstOrNull { it.tag == tag?.lowercase() } ?: SYSTEM
    }
}

enum class DialogKind { NO_FLOOR, BLOCKED }

enum class SnackKind {
    COACH, // "One bell, two notes: strike the centre, strike the side"
    NO_WALL, // S17
    LOW_CEILING, // I23
    SNAPPED, // "Lined up with the wall", 0.8 s
    VIRTUAL_FLOOR, // v0.1.2 (D7): no floor in 5 s, a virtual floor stands in
}

data class SnackUi(val id: Int, val kind: SnackKind)

data class Hover(val bell: Int, val zone: Zone)

/** Everything the panels and the scene draw. */
data class ChimeUiState(
    val room: Room = Room.STARTING,
    val rack: RackPose? = null,
    val scale: Float = 1f,
    val song: Song = Song.MOLIHUA,
    /** Strokes done in Follow, or played in the demo. */
    val step: Int = 0,
    val menuOpen: Boolean = false,
    val dialog: DialogKind? = null,
    val snack: SnackUi? = null,
    val labelsShown: Boolean = true,
    val language: LanguagePref = LanguagePref.SYSTEM,
    /** The panels speak English (the chosen language, or the system's). */
    val english: Boolean = false,
    val snapped: Boolean = false,
    val tooClose: Boolean = false,
    val noWall: Boolean = false,
    val hover: Hover? = null,
    /** Debug `hover=`: the scene shows this hover instead of the pointer's. */
    val forcedHover: Hover? = null,
    /** Debug `hover=off`: no hover from the pointer ray (clean free-play pictures). */
    val hoverOff: Boolean = false,
    /** First-run coach: B6's two decals breathe once while this is set. */
    val coach: Boolean = false,
    /** Debug `freeze=1`: swings, breathing and demo timing stand still. */
    val freeze: Boolean = false,
    /** Bumped by [Turn 45] (the ghost turns 45 degrees clockwise). */
    val turnSerial: Int = 0,
    /** Debug `near=1`: the ghost is red whatever the pointer does. */
    val forcedNear: Boolean = false,
    /** Debug `place=wall`: snap to the longest wall regardless of its length. */
    val forceWall: Boolean = false,
    /** Minimum merged wall length for a snap frame (design 2.5 m; 1.3 m at 1:2; debug wallmin). */
    val wallMin: Float = 2.5f,
    /** Plane detection should run (start, placing; stopped 15 s after placing and in the background). */
    val planesOn: Boolean = true,
    val background: Boolean = false,
    /** The stroke Follow is waiting for. */
    val target: Stroke? = null,
    /** The bell the demo just played (its plate half stays gold). */
    val demoStroke: Stroke? = null,
    val struckUntilMs: Long = 0L,
    val floorKnown: Boolean = false,
    /** The floor is the virtual stand-in (D7, v0.1.2): no floor was found within 5 s. */
    val floorVirtual: Boolean = false,
    /** The scene should stand a virtual floor in (head height - 1.55 m + [floorOffsetM]) while no real one is known. */
    val virtualFloorOn: Boolean = false,
    /** User nudge of the virtual floor, metres (+ up). */
    val floorOffsetM: Float = 0f,
    /** Debug `planes=off`: plane detection is blocked (the no-floor path on the emulator room). */
    val planesBlocked: Boolean = false,
    /** D15 (v0.1.2): bell strokes are silent; remembered. */
    val muted: Boolean = false,
    /** D15: master volume level 0 low, 1 medium (default), 2 high; remembered. */
    val volume: Int = 1,
) {
    val strokes: Int
        get() = song.strokes.size
}

/** Panel controls, scene callbacks and debug hooks. */
sealed interface ChimeEvent {
    // ---- scene
    data object SceneReady : ChimeEvent

    /** [virtual]: [floorY] is the virtual stand-in, not a detected floor. */
    data class PlanesChanged(val floorY: Float?, val ceilingY: Float?, val frames: Int, val virtual: Boolean = false) : ChimeEvent

    data object PlaneTrackingFailed : ChimeEvent

    data class GhostChanged(val snapped: Boolean, val tooClose: Boolean) : ChimeEvent

    /** After a recenter the rack was left off the heading: it goes to [pose], straight ahead (D9). */
    data class RackRecentered(val pose: RackPose) : ChimeEvent

    /** The placed rack was dragged / turned to [pose] (D1 / D14, v0.1.2). */
    data class RackMoved(val pose: RackPose) : ChimeEvent

    /** A tap on the floor / trigger while placing: the scene's ghost pose (null = cannot place here). */
    data class PlaceRequested(val pose: RackPose?) : ChimeEvent

    /** A tap on a bell; [local] = the hit point in bell coordinates. */
    data class BellTapped(val bell: Int, val zone: Zone, val local: Vec3?) : ChimeEvent

    data object NothingTapped : ChimeEvent

    data class HoverChanged(val hover: Hover?) : ChimeEvent

    data class TrackingChanged(val lost: Boolean) : ChimeEvent

    // ---- panels
    data class ModeSelected(val follow: Boolean) : ChimeEvent

    data class SongSelected(val song: Song) : ChimeEvent

    data object ListenClicked : ChimeEvent

    data object FollowClicked : ChimeEvent

    data object StopClicked : ChimeEvent

    data object AgainClicked : ChimeEvent

    data object MoreClicked : ChimeEvent

    data object Turn45Clicked : ChimeEvent

    data class LabelsSelected(val shown: Boolean) : ChimeEvent

    data class LanguageSelected(val language: LanguagePref) : ChimeEvent

    data class ScaleSelected(val scale: Float) : ChimeEvent

    data object ReplaceClicked : ChimeEvent

    /** More menu "Bring here": the rack goes straight ahead of the head, facing the user (D14). */
    data object BringClicked : ChimeEvent

    data object MuteClicked : ChimeEvent

    data class VolumeSelected(val level: Int) : ChimeEvent

    /** Placing on the virtual floor: raise / lower it by [deltaM]. */
    data class FloorNudged(val deltaM: Float) : ChimeEvent

    data object ExitClicked : ChimeEvent

    data object RetryClicked : ChimeEvent

    // ---- lifecycle
    data object WentBackground : ChimeEvent

    data object CameForeground : ChimeEvent

    // ---- debug (QA hooks, debug build only)
    data class Debug(val key: String, val value: String) : ChimeEvent
}

/** One-shot effects. */
sealed interface ChimeUiEffect {
    /** A bell sounds and swings. [local] = where it was hit (bell coordinates), null = the zone's centre. */
    data class Strike(val bell: Int, val zone: Zone, val local: Vec3?, val user: Boolean) : ChimeUiEffect

    /** Follow done: one gold ripple per bell from left to right, silent. */
    data object Wave : ChimeUiEffect

    data object StopSounds : ChimeUiEffect

    data object ExitApp : ChimeUiEffect

    data object Click : ChimeUiEffect

    /** More menu "Bring here": the scene puts the placed rack straight ahead (it knows the head). */
    data object BringRack : ChimeUiEffect
}
