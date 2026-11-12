#!/usr/bin/env bash
# Decides whether this push releases, without any signing secrets.
#  - A push to main releases when it changes versionName. The release
#    creates tag v<versionName> on the pushed commit.
#  - A push of tag v<versionName> releases that commit. The tag must match
#    the versionName it points at.
# Writes release, tag, version_name and version_code to $GITHUB_OUTPUT.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=version.sh
source "$here/version.sh"
cd "$here/../.."

: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"
: "${GITHUB_SHA:?GITHUB_SHA is required}"

name="$(read_version_name "$GRADLE_FILE")" || {
  echo "::error::${GRADLE_FILE} must set versionName \"...\" on one line."
  exit 1
}
require_version_name "$name"
tag="v${name}"

if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
  echo "::error::The checkout must have full history. versionCode is git rev-list --count HEAD."
  exit 1
fi
code="$(git rev-list --count HEAD)"

release=false
if [ "${GITHUB_REF_TYPE:-}" = "tag" ]; then
  if [ "${GITHUB_REF_NAME:-}" != "$tag" ]; then
    echo "::error::Tag ${GITHUB_REF_NAME:-unknown} does not match versionName ${name}. Expected ${tag}."
    exit 1
  fi
  release=true
  echo "Tag ${tag} releases ${GITHUB_SHA}."
elif [ "${GITHUB_REF_TYPE:-}" = "branch" ] && [ "${GITHUB_REF_NAME:-}" = "main" ]; then
  before_name="$(version_name_at "${BEFORE_SHA:-}")"
  if [ "$before_name" = "$name" ]; then
    echo "versionName ${name} is unchanged. Nothing to release."
  else
    set +e
    sha="$(remote_tag_commit "$tag")"
    status=$?
    set -e
    if [ "$status" -eq 0 ] && [ "$sha" != "$GITHUB_SHA" ]; then
      echo "::error::versionName changed to ${name}, but tag ${tag} already exists at ${sha}."
      exit 1
    elif [ "$status" -gt 1 ]; then
      echo "::error::Could not list tags on origin."
      exit 1
    fi
    release=true
    echo "versionName ${before_name:-none} -> ${name}. Releasing ${tag} at ${GITHUB_SHA}."
  fi
else
  echo "::error::Releases run on main or a v*-foldcade.* tag, not ${GITHUB_REF_TYPE:-unknown} ${GITHUB_REF_NAME:-unknown}."
  exit 1
fi

{
  echo "release=${release}"
  echo "tag=${tag}"
  echo "version_name=${name}"
  echo "version_code=${code}"
} >>"$GITHUB_OUTPUT"
