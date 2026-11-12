# Agent guide

Foldcade Moonlight is a GPL-3.0 fork of [moonlight-android](https://github.com/moonlight-stream/moonlight-android). It is becoming a two-screen streaming client for [Foldcade](https://github.com/predmore/foldcade) on the AYN Thor (Android 13, two displays). Read [README.md](README.md) first.

## Public repository

This repository is public. Never commit:

- Absolute paths from your machine, such as SDK, JDK, home, or volume paths.
- `local.properties`, keystores, signing passwords, tokens, or other credentials.
- Personal details: names, email addresses, hostnames, IP addresses, or device serials.
- Screenshots, recordings, logs, or pairing data from a real device or computer.

Put machine settings in environment variables or in `local.properties`, which Git ignores. Keep QA artifacts outside the repository.

## Build

Requirements are in [README.md](README.md#building). `JAVA_HOME` must point at JDK 21 and `ANDROID_HOME` at the SDK, unless `local.properties` sets `sdk.dir`.

```sh
git submodule update --init --recursive
./gradlew :app:assembleNonRootDebug   # debug APK
./gradlew :app:lintNonRootDebug       # what CI checks besides the build
```

Use the `nonRoot` flavor. The `root` flavor is for rooted Android 7.1 and older devices and is not used on the Thor. Upstream has no JVM unit tests.

## Releases

Changing `versionName` in `app/build.gradle` (form `12.2-foldcade.1`) and merging to `main` releases tag `v<versionName>`. CI computes `versionCode`. Never push upstream's tags (`git push --tags`). See [docs/releases.md](docs/releases.md).

## Layout

- `app/src/main/java/com/limelight/`: the Java app. The namespace stays `com.limelight` to keep upstream merges clean. Only the application ID changed.
- `app/src/main/jni/`: the native decoder and streaming glue. `moonlight-core/moonlight-common-c` is a submodule.
- `app/build.gradle`: flavors, application IDs, and the `ndkVersion`.

## Working with upstream

- The `upstream` remote is moonlight-android. Merge `upstream/master`; don't rebase published history.
- Keep edits to upstream files small. Put Foldcade features in new classes or packages, such as `com.limelight.foldcade`, so they rarely conflict.
- Don't change the application ID back to `com.limelight`. Upstream asks every fork to keep its own.

## Licensing

Everything here is GPL-3.0. MIT code from Foldcade can be copied in with its MIT notice. GPL code from here must not go into Foldcade, which is MIT. The two apps stay separate programs that talk through intents, shortcuts, and content providers. See [README.md](README.md#license).
