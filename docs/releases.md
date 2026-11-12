# Releases

Each release is a GitHub release with one asset, `foldcade-moonlight.apk`, signed with Foldcade's release key. Foldcade's plugin catalog installs and updates the plugin from `releases/latest`. The tag is the GPL source for that APK.

## Versions

`versionName` in [`app/build.gradle`](../app/build.gradle) names the release. It is the upstream version this build is based on, then `-foldcade.<n>`, for example `12.2-foldcade.1`. The tag is `v` and the `versionName`: `v12.2-foldcade.1`. The suffix keeps these tags apart from upstream's own tags (`v12.2` and older), which every clone that fetched `upstream` also has. Those tags never start a release, and they should never be pushed to `origin`. Use `git push origin <tag>`, not `git push --tags`.

Start `<n>` at 1 for each new upstream version and count up from there: `12.2-foldcade.2`, then after an upstream merge `12.3-foldcade.1`.

`versionCode` is `git rev-list --count HEAD`, which release CI passes as `-PfoldcadeVersionCode`. It grows with every commit on `main`, so each release installs over the last one. The `versionCode` written in `app/build.gradle` is upstream's and is only a fallback for local builds. Leave it to upstream merges.

## Releasing

1. In a pull request, change `versionName`. Add release notes at `.github/release/notes/v<versionName>.md` if you want them. Without that file, GitHub generates notes from the merged pull requests.
2. CI checks that `versionName` has the right form, and that its tag doesn't exist yet.
3. Merge. The `Release` workflow builds and signs the nonRoot APK, creates the tag on the merged commit, and publishes the release as Latest.

You can also push a tag yourself. It must match the `versionName` at the commit it points at:

```sh
git tag v12.2-foldcade.1 <commit>
git push origin v12.2-foldcade.1
```

This is how to release a `versionName` that is already on `main`, such as the first release, and how to retry once the secrets are fixed. Re-running a failed `Release` run works too. It leaves the tag alone and replaces the APK.

## What the workflow checks

[`release.yml`](../.github/workflows/release.yml) runs on pushes to `main` and on `v*-foldcade.*` tags. A push to `main` that doesn't change `versionName` stops after the first job and never reads the signing secrets. A release fails, before anything is published, if:

- the tag doesn't match `versionName`, or the tag already points at another commit;
- a signing secret is missing, or the keystore's key is not Foldcade's (its certificate SHA-256 is pinned as `FOLDCADE_SIGNER_SHA256`);
- the build, `lintNonRootRelease`, or the JVM tests fail;
- the APK isn't signed only by that key, or its package, `versionCode`, or `versionName` is wrong;
- its `versionCode` is not above the latest release's, so Foldcade wouldn't offer it as an update.

Then it checks that the release is public, not a pre-release, and carries `foldcade-moonlight.apk`. The scripts are in [`.github/release/`](../.github/release/).

## Signing secrets

Copy these four secrets from Foldcade's repository into this repository's Actions secrets:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

They must be Foldcade's own release key. Foldcade's catalog installs `app.foldcade.moonlight` only when its signer is Foldcade's. Never commit the keystore or its passwords.

A local signed build reads `RELEASE_KEYSTORE_FILE` (the path to a private keystore file), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` from the environment. Don't publish a local build. It has the fallback `versionCode` unless you pass `-PfoldcadeVersionCode`.
