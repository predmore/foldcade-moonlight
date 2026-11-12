# Foldcade Moonlight

[![Build](https://github.com/predmore/foldcade-moonlight/actions/workflows/build.yml/badge.svg)](https://github.com/predmore/foldcade-moonlight/actions/workflows/build.yml)

A two-screen build of [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android) made to work with [Foldcade](https://github.com/predmore/foldcade), the gaming frontend for the AYN Thor.

Moonlight streams games from a computer running [Sunshine](https://github.com/LizardByte/Sunshine), Apollo, or NVIDIA GameStream. This fork is where Foldcade-specific work happens:

- Use both of the Thor's screens while streaming.
- Reuse the computers Foldcade has already paired, so you don't pair twice.
- Start from Foldcade's Moonlight library and return to it.

The APK plugin implements Foldcade’s shared-pairing broker and dual-screen streaming entry point. The bottom screen is a companion in Foldcade's own look: a trackpad with a scroll strip, PC keys (Esc, Win, Alt+Tab, and held Alt+F4 and Ctrl+Alt+Del), typing by touch or with Foldcade's controller keyboard, live stats, mute, the stick as a mouse, stream settings with apply-and-restart, and a clear choice between Disconnect and a held Quit. It works in landscape and portrait. The Thor's Back button hands the controller between the game and the companion. See [the plugin guide](docs/foldcade-plugin.md) for the protocol, controls, testing, and release setup.

## Status

| | |
| --- | --- |
| Upstream base | moonlight-android `master`, Version 12.2 |
| Application ID | `app.foldcade.moonlight` (debug: `app.foldcade.moonlight.debug`) |
| App name | Foldcade Moonlight |
| APK plugin | Protocol 1, Moonlight platform, both displays |
| Pairing | Foldcade broker in plugin mode; upstream pairing in standalone mode |
| Companion | Compose, Foldcade's look; trackpad, PC keys, typing, stats, settings, Disconnect or Quit; fully controller-driven |
| Minimum Android | 6.0 (API 23) for the app; 8.0 for Foldcade mode |

It installs next to the official Moonlight app (`com.limelight`) and does not replace it. A compatible Foldcade build can discover it in Settings › Plugins and launch Foldcade-paired games after sharing is enabled. Opening its own launcher icon still uses the standalone Moonlight UI. A debug-only companion preview supports device QA without a paired PC.

Build and lint are required checks. Full broker-backed streaming and controller behavior across both screens also need end-to-end testing with Foldcade and a paired PC. Signed catalog releases require [Foldcade’s four signing secrets](docs/foldcade-plugin.md#signed-releases).

## Building

You need:

- JDK 21. CI uses Temurin 21.
- The Android SDK with `platforms;android-37.0`, `build-tools;37.0.0`, and `platform-tools`.
- The Android NDK version that `ndkVersion` in [`app/build.gradle`](app/build.gradle) names. Install it with `sdkmanager "ndk;<version>"`.
- The submodules: `git submodule update --init --recursive`.

Set `ANDROID_HOME` to your SDK root, or put `sdk.dir=` in a `local.properties` file. That file is ignored by Git. Then build:

```sh
./gradlew :app:assembleNonRootDebug
```

The APK is written to `app/build/outputs/apk/nonRoot/debug/`. Lint runs with `./gradlew :app:lintNonRootDebug`. JVM tests run with `./gradlew :app:testNonRootDebugUnitTest`. CI runs the build, lint, and tests on every pull request and push to `main`.

The `root` flavor targets Android 7.1 and older rooted devices (6.0 and later, since the companion's AndroidX libraries need API 23). It is kept from upstream, but the Thor uses `nonRoot`.

## Staying current with upstream

```sh
git remote add upstream https://github.com/moonlight-stream/moonlight-android.git
git fetch upstream
git merge upstream/master
```

Keep changes to upstream files small and grouped, and put Foldcade-specific code in its own files where you can, so merges stay easy.

## License

Foldcade Moonlight is licensed under the [GNU General Public License v3.0](LICENSE.txt), the license moonlight-android uses. It can't be relicensed, so every change in this repository is GPL-3.0 too.

- **Using Foldcade code here:** Foldcade is [MIT](https://github.com/predmore/foldcade/blob/main/LICENSE). MIT code can be included in this GPL project. Keep Foldcade's MIT copyright and permission notice with what you copy.
- **Using this code in Foldcade:** You can't copy GPL code from here into Foldcade's MIT codebase. Foldcade and this app stay separate programs that talk through Android's documented interfaces: intents, shortcuts, and content providers. This keeps Foldcade MIT.
- **Submodules:** `moonlight-common-c` and its own submodules keep their licenses.
- **Foldcade art and code in the companion:** its symbol icons and parts of its Compose components are copied or adapted from Foldcade under the MIT license. Foldcade's copyright and permission notice is in [`app/src/main/assets/licenses/foldcade-MIT.txt`](app/src/main/assets/licenses/foldcade-MIT.txt), and the files name it. See [NOTICE](NOTICE).
- **Nunito:** the companion's font is under the SIL Open Font License, shipped as [`app/src/main/assets/licenses/OFL-Nunito.txt`](app/src/main/assets/licenses/OFL-Nunito.txt).

If you distribute an APK, the GPL requires you to offer its complete source code too. One way is to publish it as a release in this repository, tagged at the commit it was built from.

## Credits

Moonlight is by [Cameron Gutman](https://github.com/cgutman), [Diego Waxemberg](https://github.com/dwaxemberg), [Aaron Neyer](https://github.com/Aaronneyer), [Andrew Hennessy](https://github.com/yetanothername), and the Moonlight contributors. It began as a student project at [Case Western](http://case.edu) and [MHacks](http://mhacks.org). Get the official app from [moonlight-stream.org](https://moonlight-stream.org).

This fork is not affiliated with or supported by the Moonlight project. Please report problems with this build here, not upstream.
