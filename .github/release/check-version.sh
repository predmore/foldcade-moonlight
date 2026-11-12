#!/usr/bin/env bash
# Pull request check. versionName must be <upstream>-foldcade.<n>. A pull
# request that changes it releases v<versionName> when it merges, so that tag
# must not already exist.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=version.sh
source "$here/version.sh"
cd "$here/../.."

: "${BASE_SHA:?BASE_SHA is required}"

head_name="$(read_version_name "$GRADLE_FILE")" || {
  echo "::error::${GRADLE_FILE} must set versionName \"...\" on one line."
  exit 1
}
require_version_name "$head_name"
base_name="$(version_name_at "$BASE_SHA")"

if [ "$head_name" = "$base_name" ]; then
  echo "versionName ${head_name} is unchanged. Merging does not release."
  exit 0
fi

tag="v${head_name}"
set +e
sha="$(remote_tag_commit "$tag")"
status=$?
set -e
case "$status" in
  0)
    echo "::error::Tag ${tag} already exists at ${sha}. Pick a new versionName."
    exit 1
    ;;
  1) ;;
  *)
    echo "::error::Could not list tags on origin."
    exit 1
    ;;
esac
echo "versionName ${base_name:-none} -> ${head_name}. Merging releases ${tag}."
