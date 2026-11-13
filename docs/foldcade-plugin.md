# Foldcade APK plugin

Foldcade Moonlight implements APK plugin protocol 1 as a separate GPL-3.0 app. Foldcade stays a separate MIT app. The protocol's canonical definition is in Foldcade's `docs/apk-plugins.md`; neither app links or loads the other's code.

## Discovery and play

`com.limelight.foldcade.FoldcadePlayActivity` is the exported entry point for `app.foldcade.plugin.action.PLAY`. Its activity metadata declares integer `PROTOCOL=1`, `PLATFORM=moonlight`, `SCREENS=both`, and `ACCESS=gamestream`, under `app.foldcade.plugin.`.

It requires these typed extras:

| Prefix | Fields |
| --- | --- |
| `app.foldcade.plugin.extra.` | `PROTOCOL` (int, 1), `BROKER_AUTHORITY` (String), `TITLE` (String), `LAUNCH_TOKEN` (String), `TOP_DISPLAY` (int), `BOTTOM_DISPLAY` (int, or -1) |
| `app.foldcade.gamestream.extra.` | `HOST_UUID`, `HOST_NAME`, `APP_ID` (decimal String), `APP_NAME` (all Strings) |

The entry point resolves the authority with `PackageManager`. It accepts only an exported provider belonging to `app.foldcade` or `app.foldcade.debug`, with that package's `.plugins` authority. A missing or untrusted provider, invalid extras, or unavailable top display produces a clear message and finishes on dismissal. Package visibility declarations let the app find either Foldcade build.

On a worker thread, `BrokerClient` calls `hello`, validates the host package and protocol, then calls `gamestream.hosts` and selects the exact `HOST_UUID`. It constructs transient `ComputerDetails` from the broker's address and ports; nothing is written to Moonlight's standalone computer database. A `not_granted` reply tells the user to turn on **Share paired computers** in Foldcade › Settings › Plugins › Foldcade Moonlight. Host error messages intended for logs are never displayed to the user. Protocol error codes are preserved in `BrokerException`. `launch` and `resume` carry the play start's `LAUNCH_TOKEN`, so Foldcade only lets the plugin start the game it chose. A PLAY intent from any other app gets `not_launched`. Upstream reports every failed start as a port problem; in Foldcade mode the stream activity shows the broker's own message instead, such as "Start this game from Foldcade."

The stream starts on `TOP_DISPLAY`; its private session token connects the two activities in this process. The companion starts on `BOTTOM_DISPLAY` using `ActivityOptions.setLaunchDisplayId`. Display IDs are supplied and checked at runtime. With -1, a removed bottom display, or a display launch rejection, the stream runs on one screen. A second play request while a session exists is refused. If Android kills the process, an orphaned stream or companion activity finishes instead of restoring a session without its broker.

## Transport and credentials

The private `FoldcadeStreamActivity` subclasses upstream `Game`. Small extension points in `Game`, `NvConnection`, and `NvHTTP` inject a per-connection request transport. `NvConnection` also tracks cancellation and ownership of its native connection permit: disconnecting during a slow broker launch cannot start a late stream or release another connection’s permit. Standalone launches use the original constructors and behavior.

In plugin mode **all** GameStream HTTP requests go through `gamestream.request`, including `serverinfo`. The transport allows only `serverinfo`, `applist`, `launch`, `resume`, and `cancel`, with the protocol's query constraints. It forwards the raw query unchanged, before Moonlight's normal HTTP transport adds `uniqueid` or `uuid`. Foldcade inserts its own identity. Unsupported paths, including pairing, unpairing, and artwork, fail closed rather than falling back to a direct request.

Moonlight's existing launch/resume query still supplies `appid`, mode, HDR capabilities, audio and controller options, plus its freshly generated `rikey` and `rikeyid`. Foldcade passes those fields to the PC. RTSP, video, audio, and control use Moonlight's original direct streaming path and the negotiated session key. The local client certificate and crypto provider are never initialized for plugin mode, and neither a client key, `serverCert`, nor a local `uniqueId` extra is needed. Foldcade performs HTTPS, client authentication, certificate pinning, HTTPS port discovery, and the protocol's timeouts.

Every provider call checks that it runs off the main thread and rechecks the trusted provider. Reply validation checks `ok`, the HTTP status and XML body size, host fields, and ports. A revoked grant therefore takes effect at the next request. It does not interrupt an already running stream until another broker call is needed, as specified by protocol 1.

## Companion

The bottom screen is a Jetpack Compose UI in `com.limelight.foldcade` (Kotlin and Compose are used nowhere else). It wears Foldcade's look: Afterglow's colours, Nunito, the green-to-blue focus ring with its glow, the gliding menu highlight, switches, sliders, rise/cascade motion and Foldcade's symbol icons. Those parts are adapted from Foldcade's MIT code and art; see [Licensing](#licensing).

### Layout

Landscape puts a rail of quick tiles beside the keys and pad. Portrait (1080×1240) lays the tiles in a row under the pad, and turns Settings' categories into tabs across the top.

- **Header:** the game, the PC, and a stats pill. The pill's dot is teal, amber or rose, judged on loss, ping and decode time against the frame budget. Frame rate does not colour it: a host sends only changed frames, so a still desktop runs slow. When the dot is amber or rose, the pill names the cause, for example "21 fps · 6% loss" (`StreamHealth`). A on the pill, or a tap, swaps the title for decode time, loss, and resolution/codec. With those open, the pill reads fps and ping, and the value behind an amber or rose dot (decode, loss, or the pill's ping) wears the dot's colour.
- **Time and battery:** the companion hides Android's status bar, so the header's right end shows the Thor's own clock and battery, where Foldcade's status sits, read from the edge in (`DeviceStatus`, `ui/DeviceStatusView.kt`).
  - The clock follows the locale and the 12/24-hour setting, without a day period, as Android's status bar shows it ("12:41", "21:05"). It is quieter than the pill: regular weight, no capsule, no focus stop, no tap. The status keeps the width of a two-digit hour and ends at the edge, so nothing moves at 10:00 and a low percent always sits beside its glyph.
  - The battery is Foldcade's glyph, drawn in the theme's ink so it wears any player theme: rounded to tens, a rose fill below 15%, a teal fill and a bolt while charging. At 20% and below, unplugged, the percent joins it: ink, then rose below 15% where rose reads on the theme's background.
  - It reads again each minute, on a set clock, time zone, 12/24-hour or locale change, and on every battery change, and only while the companion is visible. TalkBack reads one node, such as "12:41 PM, battery low, 12 percent"; it is not a live region.
  - The header is at least 44dp and grows with large text, so the PC's name under the title is never cut.
  - When the header runs short (portrait, large text, a pill that names a cause), it gives up in order (`HeaderFit`): the Moonlight mark; then the title, once fewer than five of its characters would show (the mark comes back if it fits, else the pill starts at the edge); then the clock. With detail open: the mark, resolution/codec, the clock, then metrics from the right. The battery glyph and a low percent never go. Each step eases, and a step the player did not cause is given back only after 10 s (30 s for the title), so a stream or charger that flickers never makes the header blink.
- **PC keys:** Esc, Win, Alt+Tab, Overlay (Win+G, the PC's game overlay), Alt+F4, Ctrl+Alt+Del and Type. Keys are opaque glass, so the backdrop never cuts through them. Alt+F4 and Ctrl+Alt+Del carry a small rose ring and need a hold: touch-and-hold, or hold A until the key fills. A short press shakes the key and shows "Hold to send" once. An accessibility service's activation asks first ("Send Alt+F4?") instead of sending, and a disabled key refuses it. A key pulses as its stroke goes out. `PcShortcut` builds the strokes.
- **Trackpad:** a glass pad with a dot grid, a light under each finger, a wheel-like scroll strip on the right, and Click and Right-click buttons, each marked with a mouse whose matching half is lit. Fingertip glyphs show Click (one dot), Right-click (two), Scroll (two and an up-down arrow) and Drag (a double-tap mark). Each one fades only after its own gesture is used, and the rest slide together as it goes: `TouchpadGesture` reports each gesture as it happens, and the set of learned gestures is kept between sessions. The pad is also a D-pad target: Down from the keys lands on it, and A toggles the stick mouse. All gesture logic is in `TouchpadGesture`.
- **Rail:** Mute, Stick mouse, Settings and End, on opaque tiles. Mute's icon cross-fades between Muted and Sound.
  - Mute is this device's media volume; unmuting at volume 0 raises it to a quarter, and a mute the companion set is undone when the session ends.
  - Stick mouse is Moonlight's controller mouse emulation. Turning it on with the controller hands the controller back to the game, since only the game's controller moves the pointer, and the game's screen and the companion's footer then show "Stick mouse · Hold Start to stop". A tap turns it on or off and leaves the controller where it is, as does turning it off. On the pad, A reads "Stick mouse off" while it is on.
  - On is teal: the tile keeps its card, and its symbol, label and a 1.5dp edge turn teal. The focus colours mean focus, and fill only the Apply pill.
  - Settings shows an amber dot while a change waits for a restart.
- **Messages** are in Foldcade's dialog style, each button wearing its controller button. A Quit the PC refused shows "Game didn't quit · Your PC kept it running." with [B Disconnect] and [A Try again]. A settings restart that cannot open the new stream shows "Couldn't restart the stream", and the session ends when it is dismissed. A Try again that fails at once shakes the card and reads "Still running. Try again or disconnect." The sheet under a message fades out entirely. After a message, focus returns to the End sheet's Quit, the deck's End tile, or Esc.
- **End** opens two cards. **Disconnect** (an exit arrow, teal) leaves the game running and is focused first. **Quit game** (Foldcade's power verb, rose) needs a hold. The deck's End tile wears the same exit arrow, so the power mark belongs only to Quit.

### Typing

- **By touch:** Type opens Android's keyboard. A trackpad takes the room above it, so clicking and scrolling need no closing, with a "Take controller" chip in its corner, and below that a strip of Esc, Tab, Erase, Enter and Done.
- **With the controller:** while the companion owns the controller, Type opens Foldcade's controller keyboard instead (adapted from Foldcade's MIT `ControllerKeyboard`). `PcKeyboard` sends each key to the PC as it is pressed. Foldcade's bindings hold: A presses the focused key, X erases, Y types a space, L1 and R1 move the caret, L2 steps Shift (one capital, then caps lock), Start is Done, B closes, and R2 is Enter.
  - **Chips:** Esc · Tab · Ctrl · Alt · ← ↑ ↓ → · F1–F12. Ctrl and Alt latch for the next key and light while latched; with one latched, a letter goes out as its key (Ctrl+A, not "a"). F1–F12 swaps the number row for F-keys until it is pressed again.
  - **Keys:** each mark appears once, and Shift gives its partner as a PC keyboard does. The keyboard includes `` ` `` and `~` for game consoles, and `\` and `|` beside Space.
  - **Echo:** a faint echo of what was typed sits above the keys, with latched modifiers after it ("Ctrl +"). It stays on the bottom screen and is cleared when the keyboard closes. A shortcut starts it over, and the shortcut itself ("Ctrl+A") stands there for a moment.
  - **Size:** height to spare makes the key rows taller, by up to a third, instead of leaving a gap.
  - **Taps:** a tap types the key and moves focus there. At twelve across a key is about 40dp, short of a finger's 48dp, so "Touch keys" is still there: it switches to Android's keyboard and hands the controller back. Small chips such as Touch keys get a 48dp touch area around the visible pill.
- **Switching:** taking the controller while Android's keyboard is up swaps it for the controller keyboard. The Thor's Back reaches the handover through `RemoteKeyboardView.dispatchKeyEventPreIme`, so the IME never takes it as "close". B closes the keyboard and keeps the controller. Handing the controller back closes the controller keyboard.

### Who has the controller

The game owns the controller. Every key and stick motion that reaches either window goes to the stream's existing handlers, as before. The player hands the controller to the companion with the **Thor's Back button**. Back comes from a controller that also has a real Select, so Moonlight only maps it to a second Select and games lose nothing. A physical keyboard uses **Ctrl+Alt+Shift+M**. "Take controller" in the footer can be tapped too. Touch never moves the controller otherwise.

While the companion owns it:

- Until the player first uses Back, the footer's "[Back] Take controller" chip is lit with a focus-coloured edge while the game has the controller. One chip teaches Back, rather than two saying the same.
- The screen's edge wears the focus ring's light, the focused control has the ring, and the footer shows an amber "Game input paused" chip with hints.
- The game's screen shows "Controller on bottom screen · [Back] Return".
- The D-pad (keys or hat) and the left stick move focus, with repeat. A selects, or holds for the hold keys. B goes back a level, and on the deck returns the controller. L1 and R1 page through Settings. Start opens and closes Settings. Back returns the controller from anywhere.
- Nothing reaches the PC. Taking the controller first releases everything the game was holding: keyboard keys and modifiers through the stream's own key path, then every button, stick, trigger and the gyro (`ControllerHandler.setInputSuspended`). The suspended handler sends nothing, so sensors, USB controllers, controller touchpads and the stick mouse stay quiet until the controller returns. Suspension and every controller packet share one lock (`InputSuspension`, unit tested), so a USB report already on its way cannot land after the controller was put at rest. A physical mouse still moves the PC's pointer, as the companion's own trackpad does; only the controller and keys change hands. A key held through the handover has its repeats and its eventual physical release swallowed, so nothing presses it again on the PC.
- Physical controller keys reach the companion before Android's keyboard; the keyboard's own keys still go to it.

`ControllerRouter` (unit tested) decides each key's side. A key always comes up on the side it went down on, and the ownership key itself reaches neither side. Only an attached companion can take the controller; with none (one screen), Back and Ctrl+Alt+Shift+M are ordinary keys for the game.

Keys reach the stream two ways. Moonlight's `StreamView` hands them to `Game.handleKeyDown` in `onKeyPreIme`, before the activity sees them, and anything it leaves comes through `dispatchKeyEvent`. `FoldcadeStreamActivity` routes both. The companion's window keeps a focused 1×1 view that takes keys in `dispatchKeyEventPreIme`, because Android otherwise eats the first D-pad press after a touch to leave touch mode.

`NavHost` holds the companion's own focus. A focus asked for before its target exists (a Settings page that has not composed yet, after a fast R1) waits as `pending`, shows no ring anywhere, and lands when the target registers.

### Settings

Settings › Video, Audio, Controls and Trackpad, in Foldcade's layout. Every row leads with a 32dp symbol, and every value ends on one edge before the chevron gutter. Long labels wrap rather than truncate. Every stream setting is Moonlight's own preference under Moonlight's key (`CompanionSettings`), so standalone Moonlight and Foldcade mode share them:

| Category | Row | Key | Values |
| --- | --- | --- | --- |
| Video | Resolution | `foldcade_stream_resolution` + `list_resolution` | Top screen, 720p, 1080p, 1440p, 4K |
| | Frame rate | `list_fps` | 30, 60, 90, 120 |
| | Bitrate | `seekbar_bitrate_kbps` | Auto (key removed), 5–150 Mbps in 5s |
| | Codec | `video_format` | Auto, H.264, HEVC, AV1 |
| | HDR | `checkbox_enable_hdr` | |
| | Frame pacing | `frame_pacing` | Fastest, Balanced, Capped, Smoothest |
| | Fill screen | `checkbox_stretch_video` | |
| | Full color range | `checkbox_full_range` | |
| | Stats on game screen | `checkbox_enable_perf_overlay` | Moonlight's own overlay on the top screen |
| | Optimize game settings | `checkbox_enable_sops` | |
| Audio | Volume | the Thor's media volume | 0–100%; A mutes and unmutes |
| | Speakers | `list_audio_config` | Stereo, 5.1, 7.1 |
| | Play on PC too | `checkbox_host_audio` | |
| Controls | Swap A/B and X/Y | `checkbox_flip_face_buttons` | |
| | Stick deadzone | `seekbar_deadzone` | 0–20% |
| | Hold Start for stick mouse | `checkbox_mouse_emulation` | |
| | Motion controls | `checkbox_gamepad_motion_fallback` | The Thor's pad has no sensors, so Moonlight uses the Thor's |
| | Rumble | `checkbox_vibrate_fallback` | The Thor's pad has no motor, so the Thor itself rumbles |
| Trackpad | Pointer speed, Natural scrolling | `foldcade_pad_speed`, `foldcade_natural_scroll` | apply live |
| | Desktop mouse mode | `checkbox_absolute_mouse_mode` | also used by the companion's pad |

`checkbox_unlock_fps` is not offered, because Moonlight reads it only in its own settings screen. `checkbox_gamepad_motion_sensors` is left at Moonlight's default, because the Thor's controller has no sensors of its own.

Controller behaviour:

- **Leaving a row:** every row leaves to its own category: Left in landscape, Up from the first row in portrait. It never goes to the nearest one, which could switch the page.

- **Choices:** Left and Right step a choice and stop at its ends. At the first value, Left goes back to the categories, and the chevron with nowhere to go fades. A cycles forward with wrap.
- **Sliders:** they step from the value they show. On Auto, bitrate reads "20 Mbps · Auto", with the thumb at 20, and Right gives 25. A puts a slider back (Auto for bitrate). At the lowest stop, Left goes to the categories.
- **Categories:** Right (Down in portrait) or A goes into the category's last-used row, else its first.
- **Hints:** they follow the focus: "A Change · B Categories" on rows, "A Open · B Close" on categories, and "A Restart" on Apply.

**Resolution in Foldcade mode.** The stream uses the top screen's native size, with its default bitrate unless a bitrate is set. This holds unless the player chose a resolution here, or chose one in standalone Moonlight before this key existed. Choosing a size here writes Moonlight's `list_resolution` and marks it `chosen`, so even 720p is honoured.

When a setting a new stream reads differs from what the running stream started with, an **Apply · restart stream** pill rises in the card. The pill names what changed on a small dark capsule. Apply disconnects, leaving the game running on the PC, and opens a fresh stream activity for the same session; for a few seconds after, the stats pill names what the new stream wears, as "1080p60 · 25 Mbps". Its `serverinfo` finds the game running and resumes it with the same launch token. The companion stays up, and the controller goes back to the game.

Each stream activity talks to the broker through a transport bound to a generation (`StreamGate`, unit tested). A restart or a Quit advances it, so a replaced stream whose startup is still running can never send `launch` or `resume`; it gets an error, and the log says "Refused resume from replaced stream N". A request already on its way is waited for before the replacement opens or the quit goes out. If one has still not come back after 65 seconds, it could yet reach the PC, so nothing goes ahead: a restart fails with "Couldn't restart the stream", and a Quit sends no cancel and offers Try again ("The stream didn't stop in time, so the quit wasn't sent."). The broker call has no local deadline on purpose: giving up on it locally would not stop it reaching the PC. A Quit during a restart cancels it: no replacement opens. Each stream activity's intent names the generation it was opened for, so one already queued when the player quits finishes before it makes a connection.

## Token renewal and appearance

- Every `gamestream.request` carries `token`, not only `launch` and `resume`. While a session lasts, the plugin sends `serverinfo` every 2 minutes, so Foldcade's sliding expiry keeps the token valid for a later resume (contract §3, amended).
- `appearance` (optional, no scope) is called once at play start. When Foldcade answers, the companion wears the player's current theme; otherwise, and on any error, it wears Afterglow. Reply keys: `background`, `surface`, `onBackground`, `muted`, `focus`, `focusRingStart`, `focusRingEnd` and `attention` (opaque ARGB `int`s), plus `round` (float, 0–0.5) and `cardCornerDp` (float, 0–64). Anything missing or out of range is Afterglow's. A theme that sets only `focus` gets a ring in that colour, as Foldcade draws it.

## Upstream hooks

Kept small and additive:

- `Game`: `prepareStream`, `configureStreamPreferences`, `getRequestTransport`, `onConnectionCreated`, `stageFailureMessage`, plus `releaseControllerInput`, `suspendControllerInput`, `suspendPointerInput`, `setControllerMouse` and `isControllerMouseActive`.
- `ControllerHandler`: `releaseAllInput`, `setInputSuspended` (with the new `InputSuspension`), `setMouseEmulation` and `isMouseEmulationActive`. Turning mouse emulation off releases a mouse button it holds.
- `FoldcadeStreamActivity` overrides `Game.handleKeyDown` and `handleKeyUp`, which `StreamView` calls before the activity's own key dispatch, so routing sees every key.
- `NvConnection` and `NvHTTP`: the per-connection request transport.
- `app/build.gradle`: the Compose compiler plugin and Compose dependencies. `minSdk` is 23, because current AndroidX and Compose require it. Foldcade mode itself needs Android 8.

A play request Foldcade Moonlight cannot start shows "Can't start the stream" in the same style on the screen it launched on, closed with A, B, Back or a tap (`ui/StartError.kt`). When the bottom screen is unavailable, the game's screen shows "Bottom screen unavailable · Playing on one screen" for a few seconds. On one screen, Back opens an **End session** panel over the stream in the companion's style (`ui/StreamEndPanel.kt`): Disconnect ("Game keeps running") is focused and takes A or a tap; Quit game ("Closes it on the PC · Hold") takes a hold of A or a finger; B, Back or a tap outside closes it. An accessibility service's activation of Quit asks "Quit game?" first. Keys the game held as it opened are released on the PC, and their repeats and releases are swallowed as at a handover. Controllers and the mouse are held from the PC while it is up: mouse buttons come up, pointer capture is let go so the mouse can click the panel, and it is captured again when the panel closes.

Any session finish closes both activities, except a settings restart, which replaces only the stream activity. Host/network errors retain Moonlight's error message, then close both on dismissal.

## Build and test

Use the SDK/JDK requirements in the [README](../README.md#building):

```sh
./gradlew :app:assembleNonRootDebug :app:lintNonRootDebug :app:testNonRootDebugUnitTest
```

JVM tests exercise typed play extras, protocol reply/error validation, HTTP response and query limits, touchpad click/scroll/drag/strip/speed behaviour, controller routing and handover release, the stream generation gate, its drain and what a failed drain blocks, refusal of a stream queued before a Quit, serialised controller suspension, the End panel's key release, PC shortcut strokes, D-pad focus geometry, `NavHost` traps and pending focus, the controller keyboard's layout, bindings, sticky modifiers, F-key layer and echo, rows leaving to their own category, per-gesture hint tracking, bitrate stepping, settings keys and pending-restart detection, stream health, and appearance parsing.

For device QA, install **only** `app.foldcade.moonlight.debug`; do not clear or reinstall Foldcade or official Moonlight. Read displays with `adb shell dumpsys display`, then launch the debug-only companion on the bottom display:

```sh
adb install -r app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
adb shell am start --display <bottom-display-id> \
  -n app.foldcade.moonlight.debug/com.limelight.foldcade.CompanionPreviewActivity \
  [--es fc_state owned|end|end-owned|settings|settings-owned|typing|typing-owned|detail|message|confirm|quit-fail|restart|restart-fail|fresh] \
  [--es fc_category video|audio|controls|trackpad]
```

The preview has a fake session and simulated 1080p HEVC stats. It exercises layout, every screen and state, gestures, IME, holds, a simulated restart, and cleanup without any paired PC. Its exported activity and class exist only in the debug source set. Disconnect or hold Quit to end the preview. For the header's status, `--ei fc_battery 7` (with `--ez fc_charging true`) stands in for the battery, and `--es fc_time 9:59` for the clock.

Two more debug-only entry points: `StreamEndPreviewActivity` shows the single-screen End panel over a blank stand-in (`am start --display 0 -n app.foldcade.moonlight.debug/com.limelight.foldcade.StreamEndPreviewActivity`), logging choices instead of acting. `StreamRaceReceiver` drives the restart races on a real session, faster than a hand can: `am broadcast -n app.foldcade.moonlight.debug/com.limelight.foldcade.StreamRaceReceiver --es race restart-twice [--ei delay 45]` restarts again while the replacement is still starting, and `--es race restart-then-quit` quits while the old stream is stopping. The second ends the game on the PC.

To drive it with the Thor's own controller, write real events to its input device. `adb shell input keyevent` sends virtual-device keys, which Moonlight and the ownership check treat differently:

```sh
# Back (158): take or return the controller. D-pad: 544 up, 545 down, 546 left, 547 right. A 304, B 305, L1 310, R1 311, Start 315.
adb shell "sendevent /dev/input/event9 1 158 1; sendevent /dev/input/event9 0 0 0; sendevent /dev/input/event9 1 158 0; sendevent /dev/input/event9 0 0 0"
```

Find the event node with `adb shell getevent -lp`; it is the built-in "Xbox Wireless Controller".

Test refusal with the exported play activity, first with no extras, then with all fields but a nonexistent or untrusted broker authority. Confirm the clear error and dismiss it; no companion should start. To test a real session, install a compatible Foldcade build, pair a PC in Foldcade, turn on Share paired computers in Settings › Plugins, and launch a game. Verify stream placement, controller input after touching the bottom panel (with and without IME), typing, each touchpad gesture, and both local and host-initiated ends. Revoke sharing and retry a broker action to check `not_granted`. Test `BOTTOM_DISPLAY=-1` for single-screen play.

Store screenshots, recordings, and logs outside this public repository. A simulated preview cannot verify actual PC input, TLS/pairing, network streaming, real performance values, or host termination.

## Signed releases

Releases are signed with **Foldcade's own release key**, because Foldcade's catalog installs `app.foldcade.moonlight` only when its signer matches Foldcade's. An APK signed with another key still works when installed by hand, but Foldcade will not install it from the catalog. Each release publishes one asset, **`foldcade-moonlight.apk`**, and its tag supplies the corresponding GPL source. Versions, tags, the four signing secrets, and what the `Release` workflow checks are in [releases.md](releases.md).

## Licensing

The companion's icons in `app/src/main/res/drawable/fc_ic_*` and `fc_symbol_*` are Foldcade's MIT art. Its focus ring, menu highlight, switch, slider, controller keyboard (`ui/Keyboard.kt`, `PcKeyboard.kt`), motion curves, theme tokens, battery reading (`DeviceStatus.kt`) and status places are adapted from Foldcade's MIT code, and the battery glyph (`ui/DeviceStatusView.kt`) is drawn from the geometry of Foldcade's MIT battery icons. Foldcade's MIT copyright and permission notice is in [`app/src/main/assets/licenses/foldcade-MIT.txt`](../app/src/main/assets/licenses/foldcade-MIT.txt), and each copied file names it. The `fc_pc_*` icons are new to this app, drawn in Foldcade's style, and GPL-3.0. Nunito (`app/src/main/res/font/nunito.ttf`) is under the SIL Open Font License; its license ships beside it in [`app/src/main/assets/licenses/OFL-Nunito.txt`](../app/src/main/assets/licenses/OFL-Nunito.txt). Nothing from this repository goes into Foldcade.
