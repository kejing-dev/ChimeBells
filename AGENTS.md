# Chime Bells (编钟)

Twelve Zeng-Hou-Yi-style yongzhong hang 1:1 on a two-tier lacquer rack beside your real wall: look at a bell
and pinch, strike the centre for one note and the side for another (one bell, two notes); in Follow the
next drum glows so you can play a tune through. PICO OS 6, Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage
(Full Space, passthrough), package `io.embodit.bianzhong`, v0.1.0.

Design package (binding): `../spatial-app-factory/apps/bianzhong/design-package.json` (v1.2.1, archetype D,
STAGE_MIXED, single panel with popup, 19 states S0 - S18). Mockup: `../spatial-app-factory/apps/bianzhong/sample.html`.
Gate B: rack structure A (two tiers in one line, 3.8 m), low notes on the left. Skeleton: `../robot-cell`
(which took it from `../fire-plan` / `../spatial-ruler`; the ruler's `AGENTS.md` holds the SDK findings
this app relies on).

Built by the factory's build station on 2026-10-25 (debug); self-tested on emulator-5560 (AVD Pico_Emulator_6_1_fresh)
on 2026-10-26: no ANR in six clean starts, all design states captured, 30-round panel stress test. Not run on a headset.

## Structure

```
app/src/main/java/io/embodit/bianzhong/
  Main.kt                      DefaultStage { ChimeTheme { ChimeScreen() } }
  platform/                    SpatialApplication, LaunchActivity, ChimeGraph (wiring), ChimeLog
    sense/PlaneTracker         plane detection -> PlaneSurface (Stage metres); pause() keeps what it saw
    tracking/HeadTracker       HMD pose, lost-tracking sign
    input/ControllerInput      controller ray, trigger, thumbstick
    audio/BellAudio            SoundPool, 8 voices, 24 strokes from assets/bells, per-frame left/right pan
  domain/model                 Vec3 / Quat4, PlaneSurface, Bells (the 12 bells, layout, lean, zones), Songs (jianpu)
  domain/usecase               WallSnapUseCase (floor / ceiling, coplanar walls merged, snap frames),
                               PlacementUseCase (ghost floor point, 0.8 - 6 m clamp, facing, snap with
                               hysteresis, RackPose), HitSurface, ViewFrame, FacePanel, RestBody
  ui/theme                     ChimeColors (brand tokens, the only colour literals), ChimeTheme (PicoTheme,
                               16 roles inherited), typography (Noto Sans + Noto Sans SC subset)
  ui/chime                     ChimeViewModel (the whole state machine), ChimeUiState / events / effects, ChimeScreen
  ui/chime/components          ChimeStage (SpatialView + panel pool), Panels (control / follow panel, segments),
                               Overlays (More menu, dialogs, snackbar), Common (glass, keys, buttons, type roles)
  ui/chime/scene               ChimeScene (ECS frame system: ghost, snap, stick, swings, decals, hover ray,
                               plates, ripples, panning, taps), ChimeViews (RackView, GhostView, SnapOutlines,
                               Ripples), BellMeshes (procedural bell / rack / outline meshes), BellArt (bronze
                               texture, plate bitmaps), PanelRig, PanelSpec, Follower, PlaneColliders, SceneKit
  ui/chime/debug/ChimeProbe    seams for the debug receiver (no-ops in release)
app/src/debug/                 DebugCommandReceiver + DebugCommands (the QA hooks)
app/src/test/                  BellsTest (layout vs the design table, clearance, songs, zones, snap)
tools/synth_bells.py           modal synthesis of the 24 strokes (standard library only)
tools/subset_cjk_font.py       Noto Sans SC subset to the copy's characters (needs fontTools)
tools/make_icon.py             the 512 export and the legacy launcher PNG (PIL)
```

One direction of data: panel controls, scene callbacks (planes, ghost snap / too close, taps, hover) and
debug hooks -> `ChimeViewModel.onEvent` -> `ChimeUiState` -> `ChimeScene.render` and the panels; strokes,
the done wave and stop / exit are one-shot `ChimeUiEffect`s. Per-frame work lives in `ChimeScene.onFrame` only.

## The rack and the bells

- Rack coordinates: origin on the floor below the lower beam, +X to the viewer's right, +Z towards the viewer.
  Footprint 3.8 x 0.9 m (x +-1.9, z -0.40 .. 0.50); posts 0.12 x 2.25 m at x = +-1.80, bases 0.60 x 0.30 x 0.08,
  beams 3.60 x 0.12 x 0.10 above the hanging points (1.30 / 2.00 m), 2 cm cinnabar band, two bronze hooks per bell.
- Bells (`Bells.all`, design table): B1 - B6 lower, B7 - B12 upper, low on the left. Each hangs from its xuan
  ring, **h_hang = 0.22 x height** below the shank top (measured on the procedural template and written back to
  the design package), leaning 20 degrees mouth-forward. Shank tops / mouths / drums / clearance match the
  design table within 2 mm (`BellsTest`); clearance 0.128 m.
- Procedural yongzhong (`BellMeshes.template`, one mesh scaled per bell): almond section with sharp corners,
  arched mouth, shank + heng cap + xuan collar, 36 bosses; physically based bronze (metallic 0.7, roughness 0.45,
  6 % emission) with a run-time texture: zheng panel and bosses bands, gold-inlaid inscription outlines on the
  front and side drums, verdigris. Rack in black lacquer.
- Hit: the whole bell is one box collider (+ InteractableComponent + HoverEffectComponent) on the bell's pivot;
  zone by the hit point in bell coordinates: |x| < 0.25 x width = front drum (zheng), else side drum (ce).
- Decals (3 per bell, hidden by default): hover 60 %, Follow target breathing 0.3 - 0.8 at 1 Hz, strike flash
  100 % for 0.12 s, the first-run coach breathes B6's centre then side once (1.2 s each).
- Plates: one textured quad per bell 6 cm below the mouth, width max(0.22 x height, 9 cm), turned about the
  vertical to the head; "宫 C4 | 角 E4" (Chinese) / "C4 | E4" (English); the struck / hovered / target half goes gold.
- Swing: damped, 2.5 degrees (lower) / 4 degrees (upper), period 1.2 s, gone in 1.5 s, strokes add up; ripples
  0.2 -> 1.2 x width in 0.4 s on the face that was hit.
- Sound: `tools/synth_bells.py` -> 24 wav (6.8 MB, stored uncompressed): six partials 1.00 / 1.52 / 2.06 / 2.75 /
  3.40 / 4.10 with twin pairs on the 2nd and 3rd, T60 4.5 / 3.2 / 2.2 s, 4 ms mallet noise, the other drum's
  fundamental at -14 / -18 dB, upper tier 2 dB quieter. Left / right volumes from the bell's bearing (+-60
  degrees -> 0.2 .. 1.0), updated every frame for ringing voices.

## Interaction

- Start: Stage opens, plane detection starts (S0 -> S1). First FLOOR -> placing (S2). 60 s without a floor ->
  "No floor found" (S13); plane detection refusing to start -> "Spatial features unavailable" (S14).
- Placing: the gold ghost follows the pointer's floor point (controller ray, else the head's view line; smoothed
  0.12 s; 0.8 - 6 m from the head, red and not placeable below 0.8 m), faces the viewer until turned. Thumbstick
  X turns it about its vertical axis (dead zone 0.15, 90 degrees per second, 0.15 s smoothing); [Turn 45]
  turns it 45 degrees clockwise. Snap outlines (dashed gold 3.8 x 0.9 m, 0.10 m off the wall) in front of every
  merged wall of 2.5 m or more (1.3 m at 1:2), at most three, nearest first, brightening as the ghost nears;
  within 0.35 m and 20 degrees it snaps (position and heading, solid outline, "Lined up"), letting go at 0.45 m /
  25 degrees. A floor tap places at the tap point (or the held frame), the trigger's press edge at the ghost.
  The hint line reads "看向地面捏一下放下 · 摇杆左右转动". After placing, plane detection stops in 15 s.
- Free play (S3): pinch / click / trigger on any part of a bell strikes it. Hover (S4) comes from the
  pointer ray each frame. First placement shows the coach snackbar once (6 s or the first strike).
- Follow (S6 - S9): three songs (Jasmine 36, Twinkle 42, Ode to Joy low 30 strokes); [Listen] plays at the
  song's bpm (strokes are real strikes), [Play along] lights the next drum, a wrong strike rings but does not
  advance, the last one ends in a silent gold wave left to right; [Again] / [Listen] / another song.
- More: note names show / hide (hidden: only the hovered bell's plate), language (system / 中文 / EN), size 1:1 /
  1:2 (rack at 0.5, chip "1:2 缩小显示"), Move rack (back to placing), Exit. With the menu open the first tap
  anywhere, bells included, only closes it.
- Background: sounds stop, plane detection pauses (surfaces kept), the demo stops; back in front the rack and
  the Follow step are where they were; unplaced states resume plane detection.
- Saved: four flags only (language, names, size, coach seen); the rack is placed again on every start.

## Panels (all SpatialUI inside PicoTheme; no Material)

Pooled attachment panels (`PanelSpec`), 1 dp = 0.8 mm at scale 1: `controlPanel` 400 x 168 and `followPanel`
400 x 264 at 0.75 (lazy follow 18 degrees left, 24 - 40 degrees below the horizon, 0.6 m), `menuPanel` 256 x 248
above it, `dialogPanel` 400 x 236 at 1.125 0.9 m ahead, `snackPanel` 560 x 56 head-locked 14 degrees up.
Dialogs and the snackbar are self-built (system dialog / snackbar compose nothing in a Stage). Copy:
`res/values` (en) and `res/values-zh`, read through `rs()` so the language switches without a restart.

## QA hooks (debug build only)

```
adb -s <serial> shell "am broadcast -a io.embodit.bianzhong.DEBUG -p io.embodit.bianzhong --es cmd 'place=auto'"
adb -s <serial> shell "am broadcast -a io.embodit.bianzhong.DEBUG -p io.embodit.bianzhong --es cmd 'mode=follow,song=molihua,step=5 freeze=1'"
adb -s <serial> shell "am broadcast -a io.embodit.bianzhong.DEBUG -p io.embodit.bianzhong --es state S2 --es near 1"
```

The design's `key=value` words in one `cmd` string (pairs separated by spaces, or by commas before the next
`key=`) or as separate extras; applied in a fixed order (settings, freeze, room, actions, queries).

| key | effect |
|---|---|
| `state=S0\|S1\|S2\|S2a\|S3\|S6\|S13\|S14\|S17` | straight to a room state (S2a forces the longest wall, S17 = no outlines + snackbar) |
| `place=auto\|wall` | auto: rack 2.5 m ahead facing the viewer, free play; wall: back to placing, snapped to the longest wall 1.5 - 6 m ahead of the viewer (else the longest anywhere) |
| `near=1` | placing with the red "too close" ghost |
| `floor_timeout=<s>` / `wallmin=<m>` (99 = none) / `ceiling=<m>` | timers and thresholds (S13, S17, low-ceiling snackbar) |
| `strike=<1-12>,<zheng\|ce>` | strikes a bell (sound, swing, ripple, flash; Follow logic applies) |
| `hover=<1-12>,<zheng\|ce>` / `hover=0` | forces the hover highlight (S4, S15 + hover) |
| `mode=free\|follow\|pick\|demo`, `song=molihua\|xiaoxingxing\|huanlesong`, `step=<n>`, `done=1` | Follow states (places the rack first when needed) |
| `scale=1\|2`, `lang=zh\|en\|system`, `labels=0\|1` | More menu settings |
| `coach=1`, `coach_reset=1` | replays / re-arms the first-run coach |
| `hover=off` | no hover from the pointer ray (clean pictures; the gaze ray of the fixed emulator camera otherwise lights a bell) |
| `freeze=1\|0` | stops swings, ripples, breathing, the demo clock and snackbar timers (2 - 4 fps safe) |
| `menu=1\|0`, `stop=1`, `replace=1`, `retry=1`, `exit=1` | panel actions |
| `ui=<control>` | presses a panel control by name (more, turn45, mode_free, mode_follow, song_*, listen, follow, stop, again, labels_*, lang_*, size_*, menu_replace, menu_exit, dialog_exit, dialog_retry, chip_half) |
| `dump=1`, `hmd=1`, `bounds=1`, `planes=1` (planes + snap frames), `park=1` | queries |

Log tag `Bianzhong`: `STATE <Sxx> overlays=S4,S5,S10,S12,S15,S16 song=.. step=n/N hover=..` on every change;
`strike=B6,zheng,pan=0.70,1.00`, `TAP`, `HOVER`, `SNAP on|off`, `WALLS`, `PLANES`, `PLACE`, `FOLLOW hit|miss|done`,
`SNACK`, `CMD`, `DUMP`.

Screenshot list (design `stateModel.qaCombos`, 27):

| shot | broadcast (`--es cmd '...'`) |
|---|---|
| S0 | `state=S0 freeze=1` (or a cold start within 2 s) |
| S1 | `state=S1` |
| S2 | `state=S2` (ghost in the middle of the room, not snapped) |
| S2 too close | `state=S2 near=1` |
| S2a | `place=wall freeze=1` |
| S17 | `state=S17` |
| S3 | `place=auto` |
| S3 + coach | `coach=1 freeze=1` |
| S4 B6 centre / side | `hover=6,zheng` / `hover=6,ce` (or the mouse over B6) |
| S5 B6 centre / B1 side | `freeze=1 strike=6,zheng` / `freeze=1 strike=1,ce` |
| S6 | `mode=pick` |
| S7 Jasmine 12/36 | `mode=demo,song=molihua,step=12 freeze=1` |
| S8 Jasmine 5/36 | `mode=follow,song=molihua,step=5 freeze=1` |
| S8 after a miss | then `strike=1,zheng` (target and count unchanged) |
| S8 Ode to Joy (big bells) | `mode=follow,song=huanlesong,step=1 freeze=1` |
| S9 | `mode=follow,song=molihua,done=1 freeze=1` |
| S10 | `menu=1` |
| S12 | `scale=2 place=auto` |
| S13 / S14 | `state=S13` / `state=S14` |
| S15 / S15 + hover | `labels=0 place=auto` / then `hover=6,zheng` |
| S16 | `lang=en place=auto` |
| S18 -> S8 / S18 -> S2 | Home and back while in S8 / in S2 |

`state=...` does not clear a freeze: send `freeze=0` before interactive checks.

## Build

```
# PowerShell (Git Bash fails on gradlew: CRLF)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug --no-daemon
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <serial> shell monkey -p io.embodit.bianzhong -c android.intent.category.LAUNCHER 1
```

The incremental packager leaves holes in the APK: delete `app/build/outputs/apk/debug/app-debug.apk` and
`app/build/intermediates/incremental/packageDebug` for a size check (clean: 38.6 MB, 30 MB of it debug dex).
Material gate: `grep -rEf ../spatial-app-factory/bin/material-grep.txt --include=*.kt --include=*.kts app/src`
must be empty. Unit tests: `.\gradlew.bat testDebugUnitTest --no-daemon`.

## Deviations from the design package (recorded in the factory's state.json, all within tolerance)

- Control panel as two pooled panels (`controlPanel` 168 dp, `followPanel` 264 dp) instead of one that grows:
  a pooled attachment panel has a fixed size. Pool = 5 panels instead of 4.
- Hover (S4) is computed from the controller ray, or the head's view line, against each bell's box every frame:
  the system pointer gives no hover callbacks to the app. The system HoverEffectComponent highlight is also on.
  On the emulator the gaze is the screen centre, not the mouse.
- Bells and rack are procedural (the design's own first-day tier; no CC0 scan was looked for). The cloud-and-
  thunder band on the beams is a plain cinnabar band; bosses are short cones, no normal-map relief.
- Ripple pool 12 instead of 6 (a frozen done-wave keeps all twelve on screen).
- Controller vibration (I10) not implemented (interface unconfirmed); no spatial audio component, left / right pan.

## Emulator self-test (2026-10-26, emulator-5560, Pico_Emulator_6_1_fresh)

- Tools: `tools/launch_check.sh [s]` (force-stop, cold start, watches for "isn't responding" / am_anr),
  `tools/qa_shot.sh NAME 'key=value ...' ...` (broadcasts, `fast on`, console frame), `tools/shoot_all.sh`
  (all 25 design shots into `../spatial-app-factory/apps/bianzhong/build/shots`), `tools/stress_panels.sh [n]`
  (Follow panel in, menu open / close, back to free play; checks the panel area for black), `tools/harness/`
  (emulator input, from access-check). The Stage origin is the camera at launch: `place=auto` puts the rack 2.5 m
  ahead, which frames it whole.
- Startup: the scene is built one mesh / one bell per paced step (600 ms between steps while the emulator renders
  at 1 - 2 fps, one frame on a headset): about 40 s of S0 on the idle emulator, plane detection running in
  parallel (57 planes, floor 0.40, 19 walls, 3 snap frames). Six clean starts, no ANR.
- Found and fixed: the bronze base-colour texture is sampled with v from the bottom on the physically based
  material (the inscriptions sat under the shoulder): the bitmap is drawn upside down; emission 25 % washed the
  bronze out to beige: 6 %, metallic 0.7. A pooled panel whose rows changed at 1 - 2 fps sometimes stayed on a
  half-recorded frame (black under the first rows until the next content change; a root alpha / recomposition did
  not clear it): `Glass(refreshKey)` rebuilds the rows 1.5 / 4 / 8 s after every change; after it 0 black panels
  in 25 shots and 20 switch checks; the 30-round stress test (no crash) caught the hint row black twice at 5.5 s,
  i.e. during the panel's own rebuild, clean again on re-check. `place=wall` picks a wall in front of the camera; `state=S17` clears a forced snap;
  a freeze no longer keeps the coach snackbar forever.
- Sound: every strike logs `strike=B6,zheng,pan=0.43,0.77 user=true audio=ok` (SoundPool accepted the stream;
  emulator audio output itself not listened to). Left bells pan left, right bells right.
- Background (S18): HOME -> "Back to Home" (the system dialog draws the app icon: `build/launcher.png`) -> Confirm,
  monkey relaunch: S8 came back on the same stroke (5/36) with the rack in place; S2 came back placing.
- Not exercised: the thumbstick (controller DEVICE_NOT_SUPPORTED in eye mode), a real pinch on a bell (strikes and
  hovers were driven by the debug hooks; the system HoverEffectComponent lights the bell under the emulator mouse:
  the pale B2 in the shots).

## Not verified

On a headset: the real-room scale and wall snapping, the thumbstick, eye hover + pinch feel on the upper bells,
how the bells sound and how fast, the bronze in real room light.
