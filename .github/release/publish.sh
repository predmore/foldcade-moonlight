#!/usr/bin/env bash
# Publishes foldcade-moonlight.apk as the latest GitHub release for $TAG,
# with the complete source archive (submodules included) that the GPL asks for.
# Foldcade's catalog reads releases/latest and that asset name, and installs
# the APK only when its versionCode is above the installed one.
# Creates the tag on $GITHUB_SHA when it is missing. A tag created with the
# workflow token does not start another workflow run. A tag on another commit
# is never moved. Running it again on the same commit is safe: it replaces
# the asset and leaves the tag alone.
set -euo pipefail

apk="${1:?path to foldcade-moonlight.apk}"
source_archive="${2:?path to the source archive}"
: "${TAG:?}" "${VERSION_NAME:?}" "${VERSION_CODE:?}" "${GITHUB_SHA:?}" "${GITHUB_REPOSITORY:?}"

asset="foldcade-moonlight.apk"
if [ "$(basename "$apk")" != "$asset" ] || [ ! -s "$apk" ]; then
  echo "::error::Expected a non-empty ${asset}, got ${apk}."
  exit 1
fi
if [ "$(basename "$source_archive")" != "foldcade-moonlight-${TAG}-source.tar.gz" ] || [ ! -s "$source_archive" ]; then
  echo "::error::Expected a non-empty foldcade-moonlight-${TAG}-source.tar.gz, got ${source_archive}."
  exit 1
fi

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
repo="repos/${GITHUB_REPOSITORY}"

# Commit the tag points at on GitHub, or fail when there is no such tag.
tag_commit() {
  local json type sha
  json="$(gh api "${repo}/git/ref/tags/${TAG}" 2>/dev/null)" || return 1
  type="$(jq -r '.object.type' <<<"$json")"
  sha="$(jq -r '.object.sha' <<<"$json")"
  if [ "$type" = "tag" ]; then
    sha="$(gh api "${repo}/git/tags/${sha}" --jq '.object.sha')"
  fi
  printf '%s\n' "$sha"
}

if sha="$(tag_commit)"; then
  if [ "$sha" != "$GITHUB_SHA" ]; then
    echo "::error::Tag ${TAG} already points at ${sha}, not ${GITHUB_SHA}. Refusing to move it."
    exit 1
  fi
  tag_exists=true
else
  tag_exists=false
fi

# The catalog only installs a higher versionCode than the one it has.
if latest="$(gh api "${repo}/releases/latest" 2>/dev/null)"; then
  latest_tag="$(jq -r '.tag_name' <<<"$latest")"
  latest_code="$(jq -r '.body // ""' <<<"$latest" | sed -n 's/^versionCode:[[:space:]]*\([0-9][0-9]*\)[[:space:]]*$/\1/p' | sed -n '1p')"
  if [ "$latest_tag" != "$TAG" ] && [ -n "$latest_code" ] && [ "$VERSION_CODE" -le "$latest_code" ]; then
    echo "::error::versionCode ${VERSION_CODE} is not above ${latest_code} in the latest release ${latest_tag}. Foldcade would not offer it as an update."
    exit 1
  fi
fi

notes="$(mktemp)"
trap 'rm -f "$notes"' EXIT
{
  printf 'versionName: %s\n' "$VERSION_NAME"
  printf 'versionCode: %s\n' "$VERSION_CODE"
  printf 'commit: %s\n\n' "$GITHUB_SHA"
  printf 'Complete source, submodules included: %s\n\n' "$(basename "$source_archive")"
  if [ -f "${root}/.github/release/notes/${TAG}.md" ]; then
    cat "${root}/.github/release/notes/${TAG}.md"
  else
    gh api "${repo}/releases/generate-notes" \
      -f tag_name="$TAG" \
      -f target_commitish="$GITHUB_SHA" \
      --jq '.body'
  fi
} >"$notes"

if gh release view "$TAG" >/dev/null 2>&1; then
  echo "Release ${TAG} exists. Replacing ${asset}."
  gh release upload "$TAG" "$apk" "$source_archive" --clobber
  gh release edit "$TAG" --notes-file "$notes" --draft=false --prerelease=false --latest
else
  args=("$TAG" "$apk" "$source_archive" --title "$TAG" --notes-file "$notes" --latest)
  if [ "$tag_exists" = true ]; then
    args+=(--verify-tag)
  else
    args+=(--target "$GITHUB_SHA")
  fi
  gh release create "${args[@]}"
fi

# The public tag endpoint 404s for drafts, so this proves Foldcade can see it.
ok=false
for wait in 0 2 4 8; do
  sleep "$wait"
  if json="$(gh api "${repo}/releases/tags/${TAG}" 2>/dev/null)" \
    && [ "$(jq -r --arg a "$asset" --arg s "$(basename "$source_archive")" '(.draft | not) and (.prerelease | not) and ([.assets[].name] | index($a) != null and index($s) != null)' <<<"$json")" = "true" ]
  then
    ok=true
    break
  fi
done
if [ "$ok" != true ]; then
  echo "::error::Release ${TAG} is missing, a draft, a pre-release, or lacks ${asset} or the source archive."
  exit 1
fi
if [ "$(tag_commit || true)" != "$GITHUB_SHA" ]; then
  echo "::error::Tag ${TAG} does not point at ${GITHUB_SHA}."
  exit 1
fi
latest_tag="$(gh api "${repo}/releases/latest" --jq '.tag_name' 2>/dev/null || true)"
if [ "$latest_tag" != "$TAG" ]; then
  echo "::warning::releases/latest is ${latest_tag:-missing}, not ${TAG}. Foldcade's catalog follows releases/latest."
fi
echo "Published ${TAG} (versionCode ${VERSION_CODE}) with ${asset}."
