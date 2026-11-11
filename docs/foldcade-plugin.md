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

- **Header:** the game, the PC, and a stats pill (fps · ping, with a dot that is teal, amber or rose for ping, loss and decode time). Tapping or pressing A on the pill swaps the title for decode time, loss, and resolution/codec. Frame rate does not colour the dot: a host sends only changed frames, so a still desktop runs slow.
- **PC keys:** Esc, Start (the Windows key), Alt+Tab, Alt+F4, Ctrl+Alt+Del and Type. Alt+F4 and Ctrl+Alt+Del show a small rose ring and take a hold: touch-and-hold or hold A until the key fills. A short press shakes the key and shows "Hold to send". Strokes are built by `PcShortcut`: modifiers down in order, the key tapped, modifiers up in reverse.
- **Trackpad:** a glass pad with a dot grid and a light under each finger, a scroll strip on its right edge, and L/R click buttons along the bottom. One finger moves the cursor relatively, with acceleration and fractional accumulation. A tap clicks left; it is held 300 ms so double-tap-and-slide can drag without a double-click first, while two quick taps double-click. A two-finger tap right-clicks; a two-finger drag scrolls on both axes. The strip works like a wheel: sliding down scrolls down, and a tap on its top or bottom half scrolls one notch. Holding L or R and moving a finger on the pad drags. Pointer speed and natural scrolling are Settings › Trackpad. All gesture logic is in `TouchpadGesture`.
- **Rail:** Mute (this device's media volume; put back when the session ends), Pad mouse (Moonlight's controller mouse emulation: the stick moves the cursor, A and B click), Settings, and End. Settings shows an amber dot while a change waits for a restart.
- **Type** opens the system keyboard on the bottom display. Text goes through `NvConnection.sendUtf8Text`; keys use `Game`'s `KeyboardTranslator` path. A strip of Esc, Tab, Erase, Enter and Done sits above the keyboard.
- **End** opens two cards. **Disconnect** (Foldcade's "still open" badge) leaves the game running and is focused first. **Quit game** (Foldcade's Close verb, in rose) takes a hold, then calls upstream `NvHTTP.quitApp()` through the broker (`cancel`, then `serverinfo` verification). A failure keeps the session open and shows a message in Foldcade's dialog style. Android's Back gesture on the bottom screen opens End; on the stream screen it does the same.

### Who has the controller

The game owns the controller. Every key and stick motion that reaches either window goes to the stream's existing handlers, as before. The player hands the controller to the companion with the **Thor's Back button**. Back comes from a controller that also has a real Select, so Moonlight only maps it to a second Select and games lose nothing. A physical keyboard uses **Ctrl+Alt+Shift+M**, beside Moonlight's own Ctrl+Alt+Shift shortcuts. For a controller without a separate Back, the footer's "Controls here" can be tapped. Touch never moves the controller otherwise.

While the companion owns it:

- The screen's edge wears the focus ring's light, the focused control has the ring, and the footer shows an amber "Game input paused" chip with hints.
- The game's screen shows a small pill, "Controller on the bottom screen · Back returns it".
- The D-pad (keys or hat) and the left stick move focus, with repeat. A selects, or holds for the hold keys. B goes back a level, and on the deck returns the controller. L1 and R1 page through Settings. Start opens and closes Settings. Back returns the controller from anywhere.
- Nothing reaches the PC. Taking the controller first releases every button, stick and trigger on the PC (`ControllerHandler.releaseAllInput`).

`ControllerRouter` (unit tested) decides each key's side. A key always comes up on the side it went down on, so a button held across the switch never sticks on the PC or in the companion, and the ownership key itself reaches neither. Volume, power and IME keys never change hands.

The companion's window keeps a focused 1×1 view that takes keys in `dispatchKeyEventPreIme`. Android otherwise eats the first D-pad press after a touch to leave touch mode, before any activity code sees it, so that press would reach neither the game nor the companion.

### Settings

Settings › Video, Audio, Controls and Trackpad, in Foldcade's layout: categories on the left, rows in a card. Every stream setting is Moonlight's own preference under Moonlight's key (`CompanionSettings`), so standalone Moonlight and Foldcade mode share them:

| Row | Key | Values |
| --- | --- | --- |
| Resolution | `foldcade_stream_resolution` + `list_resolution` | Top screen, 720p, 1080p, 1440p, 4K |
| Frame rate | `list_fps` | 30, 60, 90, 120 |
| Bitrate | `seekbar_bitrate_kbps` | Auto (key removed; shows Moonlight's default), 5–150 Mbps |
| Codec | `video_format` | Auto, H.264, HEVC, AV1 |
| HDR | `checkbox_enable_hdr` | |
| Frame pacing | `frame_pacing` | Lowest latency, Balanced, Cap frame rate, Smoothest |
| Optimize game settings | `checkbox_enable_sops` | |
| Speakers | `list_audio_config` | Stereo, 5.1, 7.1 |
| Play on PC too | `checkbox_host_audio` | |
| Swap A/B and X/Y | `checkbox_flip_face_buttons` | |
| Stick deadzone | `seekbar_deadzone` | 0–20% |
| Hold Start for pad mouse | `checkbox_mouse_emulation` | |
| Pointer speed, Natural scrolling | `foldcade_pad_speed`, `foldcade_natural_scroll` | apply live |

**Resolution in Foldcade mode.** The stream uses the top screen's native size, with its default bitrate unless a bitrate is set. This holds unless the player chose a resolution here, or chose one in standalone Moonlight before this key existed. Choosing a size here writes Moonlight's `list_resolution` and marks it `chosen`, so even 720p is honoured. Choosing Top screen marks it `native` and leaves standalone Moonlight's resolution alone.

When a setting a new stream reads differs from what the running stream started with, an **Apply · restart stream** pill rises in the card. Apply disconnects, leaving the game running on the PC, and opens a fresh stream activity for the same session. Its `serverinfo` finds the game running and resumes it with the same launch token. The companion stays up throughout, and the controller goes back to the game.

## Token renewal and appearance

- Every `gamestream.request` carries `token`, not only `launch` and `resume`. While a session lasts, the plugin sends `serverinfo` every 2 minutes, so Foldcade's sliding expiry keeps the token valid for a later resume (contract §3, amended).
- `appearance` (optional, no scope) is called once at play start. When Foldcade answers, the companion wears the player's current theme; otherwise, and on any error, it wears Afterglow. Reply keys: `background`, `surface`, `onBackground`, `muted`, `focus`, `focusRingStart`, `focusRingEnd` and `attention` (opaque ARGB `int`s), plus `round` (float, 0–0.5) and `cardCornerDp` (float, 0–64). Anything missing or out of range is Afterglow's. A theme that sets only `focus` gets a ring in that colour, as Foldcade draws it.

## Upstream hooks

Kept small and additive:

- `Game`: `prepareStream`, `configureStreamPreferences`, `getRequestTransport`, `onConnectionCreated`, `stageFailureMessage`, plus `releaseControllerInput`, `setControllerMouse` and `isControllerMouseActive`.
- `ControllerHandler`: `releaseAllInput`, `setMouseEmulation` and `isMouseEmulationActive`.
- `NvConnection` and `NvHTTP`: the per-connection request transport.
- `app/build.gradle`: the Compose compiler plugin and Compose dependencies. `minSdk` is 23, because current AndroidX and Compose require it. Foldcade mode itself needs Android 8.

Any session finish closes both activities, except a settings restart, which replaces only the stream activity. Host/network errors retain Moonlight's error message, then close both on dismissal.

## Build and test

Use the SDK/JDK requirements in the [README](../README.md#building):

```sh
./gradlew :app:assembleNonRootDebug :app:lintNonRootDebug :app:testNonRootDebugUnitTest
```

JVM tests exercise typed play extras, protocol reply/error validation, HTTP response and query limits, touchpad click/scroll/drag/strip/speed behaviour, controller routing, PC shortcut strokes, D-pad focus geometry, settings keys and pending-restart detection, and appearance parsing.

For device QA, install **only** `app.foldcade.moonlight.debug`; do not clear or reinstall Foldcade or official Moonlight. Read displays with `adb shell dumpsys display`, then launch the debug-only companion on the bottom display:

```sh
adb install -r app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
adb shell am start --display <bottom-display-id> \
  -n app.foldcade.moonlight.debug/com.limelight.foldcade.CompanionPreviewActivity \
  [--es fc_state owned|end|end-owned|settings|settings-owned|typing|detail|message|restart|fresh] \
  [--es fc_category video|audio|controls|trackpad]
```

The preview has a fake session and simulated 1080p HEVC stats. It exercises layout, every screen and state, gestures, IME, holds, a simulated restart, and cleanup without any paired PC. Its exported activity and class exist only in the debug source set. Disconnect or hold Quit to end the preview.

To drive it with the Thor's own controller, write real events to its input device. `adb shell input keyevent` sends virtual-device keys, which Moonlight and the ownership check treat differently:

```sh
# Back (158): take or return the controller. D-pad: 544 up, 545 down, 546 left, 547 right. A 304, B 305, L1 310, R1 311, Start 315.
adb shell "sendevent /dev/input/event9 1 158 1; sendevent /dev/input/event9 0 0 0; sendevent /dev/input/event9 1 158 0; sendevent /dev/input/event9 0 0 0"
```

Find the event node with `adb shell getevent -lp`; it is the built-in "Xbox Wireless Controller".

Test refusal with the exported play activity, first with no extras, then with all fields but a nonexistent or untrusted broker authority. Confirm the clear error and dismiss it; no companion should start. To test a real session, install a compatible Foldcade build, pair a PC in Foldcade, turn on Share paired computers in Settings › Plugins, and launch a game. Verify stream placement, controller input after touching the bottom panel (with and without IME), typing, each touchpad gesture, and both local and host-initiated ends. Revoke sharing and retry a broker action to check `not_granted`. Test `BOTTOM_DISPLAY=-1` for single-screen play.

Store screenshots, recordings, and logs outside this public repository. A simulated preview cannot verify actual PC input, TLS/pairing, network streaming, real performance values, or host termination.

## Signed releases

Copy these **four secrets from Foldcade's repository** into this repository's GitHub Actions secrets:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

They must represent **Foldcade's own release signing key**. Foldcade's catalog installer accepts `app.foldcade.moonlight` only when its signer set equals Foldcade's. A different key produces a usable manually installed plugin, but Foldcade will refuse to install that catalog APK.

The `Release` workflow runs on `v*` tags, refuses missing secrets, decodes a temporary keystore, builds and lints the signed nonRoot release, verifies the APK signature, and publishes the GitHub release asset **`foldcade-moonlight.apk`**. The tag and public repository supply the corresponding GPL source. Never commit the keystore or signing credentials.

Local signing reads `RELEASE_KEYSTORE_FILE` (a private file), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` from the environment. No keystore or secrets are generated by this project. Increment `versionCode` before publishing an update so Foldcade can recognize it as newer.

## Licensing

The companion's icons in `app/src/main/res/drawable/fc_ic_*` and `fc_symbol_*` are Foldcade's MIT art. Its focus ring, menu highlight, switch, slider, motion curves and theme tokens are adapted from Foldcade's MIT code. Foldcade's MIT copyright and permission notice is in [`app/src/main/assets/licenses/foldcade-MIT.txt`](../app/src/main/assets/licenses/foldcade-MIT.txt), and each copied file names it. The `fc_pc_*` icons are new to this app, drawn in Foldcade's style, and GPL-3.0. Nunito (`app/src/main/res/font/nunito.ttf`) is under the SIL Open Font License; its license ships beside it in [`app/src/main/assets/licenses/OFL-Nunito.txt`](../app/src/main/assets/licenses/OFL-Nunito.txt). Nothing from this repository goes into Foldcade.
