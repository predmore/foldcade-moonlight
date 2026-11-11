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

On a worker thread, `BrokerClient` calls `hello`, validates the host package and protocol, then calls `gamestream.hosts` and selects the exact `HOST_UUID`. It constructs transient `ComputerDetails` from the broker's address and ports; nothing is written to Moonlight's standalone computer database. A `not_granted` reply tells the user to enable sharing in **Foldcade › Settings › Plugins**. Host error messages intended for logs are never displayed to the user. Protocol error codes are preserved in `BrokerException`. `launch` and `resume` carry the play start's `LAUNCH_TOKEN`, so Foldcade only lets the plugin start the game it chose. A PLAY intent from any other app gets `not_launched`.

The stream starts on `TOP_DISPLAY`; its private session token connects the two activities in this process. The companion starts on `BOTTOM_DISPLAY` using `ActivityOptions.setLaunchDisplayId`. Display IDs are supplied and checked at runtime. With -1, a removed bottom display, or a display launch rejection, the stream runs on one screen. A second play request while a session exists is refused. If Android kills the process, an orphaned stream or companion activity finishes instead of restoring a session without its broker.

## Transport and credentials

The private `FoldcadeStreamActivity` subclasses upstream `Game`. Small extension points in `Game`, `NvConnection`, and `NvHTTP` inject a per-connection request transport. `NvConnection` also tracks cancellation and ownership of its native connection permit: disconnecting during a slow broker launch cannot start a late stream or release another connection’s permit. Standalone launches use the original constructors and behavior.

In plugin mode **all** GameStream HTTP requests go through `gamestream.request`, including `serverinfo`. The transport allows only `serverinfo`, `applist`, `launch`, `resume`, and `cancel`, with the protocol's query constraints. It forwards the raw query unchanged, before Moonlight's normal HTTP transport adds `uniqueid` or `uuid`. Foldcade inserts its own identity. Unsupported paths, including pairing, unpairing, and artwork, fail closed rather than falling back to a direct request.

Moonlight's existing launch/resume query still supplies `appid`, mode, HDR capabilities, audio and controller options, plus its freshly generated `rikey` and `rikeyid`. Foldcade passes those fields to the PC. RTSP, video, audio, and control use Moonlight's original direct streaming path and the negotiated session key. The local client certificate and crypto provider are never initialized for plugin mode, and neither a client key, `serverCert`, nor a local `uniqueId` extra is needed. Foldcade performs HTTPS, client authentication, certificate pinning, HTTPS port discovery, and the protocol's timeouts.

Every provider call checks that it runs off the main thread and rechecks the trusted provider. Reply validation checks `ok`, the HTTP status and XML body size, host fields, and ports. A revoked grant therefore takes effect at the next request. It does not interrupt an already running stream until another broker call is needed, as specified by protocol 1.

## Companion

The companion uses platform views and a dark theme, with a game/PC header, one-line performance strip, large touchpad, and three buttons. The touchpad's hint disappears after its first use.

- One finger moves the cursor relatively, with speed-based acceleration and fractional motion accumulation. Tap clicks left.
- A two-finger tap clicks right. A two-finger drag sends high-resolution vertical and horizontal scroll. The page follows the fingers on both axes.
- A left tap is sent 300 ms late, so a second touch can become a drag without a double-click first. Double-tap and move drags with the left button down; two quick taps double-click. Pointer cancellation releases a held button; lifting one finger after scrolling cannot jump the cursor.
- **Keyboard** enables a bottom-display editor and opens the system IME. Committed text goes through `NvConnection.sendUtf8Text`; keys use `Game`'s existing `KeyboardTranslator` path. Back first hides the keyboard. The touchpad and stats collapse while typing to keep the buttons reachable above large IMEs.
- **Disconnect** finishes both activities and stops the connection, leaving the PC game running. Back asks for confirmation before disconnecting.
- **Quit game** asks for confirmation, then calls upstream `NvHTTP.quitApp()` through the broker (`cancel` followed by `serverinfo` verification). A failure keeps the session open and shows a message.

The companion window stays focusable. Launching it on the bottom display makes that display the focused one, so a non-focusable window there would leave controller input with nowhere to go. Every key and controller motion the companion receives is forwarded to the stream's existing handlers, so the controller drives the game whichever screen was touched last. The stream keeps upstream's handling when it holds focus itself. Both activities allow Android's multi-display resume behavior; the plugin stream does not inherit upstream `Game`'s `noHistory` task setting.

Stats come from the decoder's numeric performance counters, approximately once per second, using the same recent windows as the overlay: rendered FPS, estimated network RTT, mean decoder time, network frame loss percentage, and stream resolution/codec. No overlay text is parsed. The top overlay and picture-in-picture are disabled only for the plugin session; saved standalone preferences are unchanged.

Any session finish closes both activities. Host/network errors retain Moonlight's error message, then close both on dismissal. The companion cannot remain after the stream activity finishes or is destroyed.

## Build and test

Use the SDK/JDK requirements in the [README](../README.md#building):

```sh
./gradlew :app:assembleNonRootDebug :app:lintNonRootDebug :app:testNonRootDebugUnitTest
```

JVM tests exercise typed play extras, protocol reply/error validation, HTTP response and query limits, and touchpad click/scroll/drag/cancellation behavior.

For device QA, install **only** `app.foldcade.moonlight.debug`; do not clear or reinstall Foldcade or official Moonlight. Read displays with `adb shell dumpsys display`, then launch the debug-only companion on the bottom display:

```sh
adb install -r app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
adb shell am start --display <bottom-display-id> \
  -n app.foldcade.moonlight.debug/com.limelight.foldcade.CompanionPreviewActivity
```

The preview has a fake session and simulated 1080p HEVC stats. It exercises layout, gestures, IME, confirmations, and cleanup without any paired PC. Its exported activity and class exist only in the debug source set. Disconnect or confirm Quit to end the preview.

Test refusal with the exported play activity, first with no extras, then with all fields but a nonexistent or untrusted broker authority. Confirm the clear error and dismiss it; no companion should start. To test a real session, install a compatible Foldcade build, pair a PC in Foldcade, grant GameStream sharing in Settings › Plugins, and launch a game. Verify stream placement, controller input after touching the bottom panel (with and without IME), typing, each touchpad gesture, and both local and host-initiated ends. Revoke sharing and retry a broker action to check `not_granted`. Test `BOTTOM_DISPLAY=-1` for single-screen play.

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
