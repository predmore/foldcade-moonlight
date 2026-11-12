#!/usr/bin/env bash
# versionName in app/build.gradle names the release, and its tag is
# v<versionName>. The name is <upstream version>-foldcade.<n>, for example
# 12.2-foldcade.1. The -foldcade suffix keeps these tags apart from upstream's
# own tags (v12.2 and older), which sit in every clone that fetched upstream.
# versionCode in that file is upstream's and only a local fallback. Release CI
# passes -PfoldcadeVersionCode from git rev-list --count HEAD.

GRADLE_FILE="app/build.gradle"
VERSION_NAME_PATTERN='^[0-9]+(\.[0-9]+)*-foldcade\.[0-9]+$'

# Prints the versionName in a build.gradle file, or fails.
read_version_name() {
  local file="$1" name
  [ -f "$file" ] || return 1
  name="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=\{0,1\}[[:space:]]*"\([^"]*\)"[[:space:]]*$/\1/p' "$file" | sed -n '1p')"
  [ -n "$name" ] || return 1
  printf '%s\n' "$name"
}

require_version_name() {
  local name="$1"
  if ! [[ "$name" =~ $VERSION_NAME_PATTERN ]]; then
    echo "::error::versionName must look like 12.2-foldcade.1 (upstream version, then -foldcade.<n>), was '${name}'." >&2
    return 1
  fi
}

# Prints the versionName in $GRADLE_FILE at a commit, or nothing when the
# commit is all zeros, or the file or line is missing there.
version_name_at() {
  local sha="$1" tmp name=""
  if [ -z "$sha" ] || [ "$sha" = "0000000000000000000000000000000000000000" ]; then
    return 0
  fi
  if ! git cat-file -e "${sha}^{commit}" 2>/dev/null; then
    # Keep a full checkout full: versionCode counts its history.
    if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
      git fetch --quiet --no-tags --depth=1 origin "$sha" || return 1
    else
      git fetch --quiet --no-tags origin "$sha" || return 1
    fi
  fi
  tmp="$(mktemp)"
  if git show "${sha}:${GRADLE_FILE}" >"$tmp" 2>/dev/null; then
    name="$(read_version_name "$tmp" || true)"
  fi
  rm -f "$tmp"
  printf '%s\n' "$name"
}

# Prints the commit a tag points at on origin, or fails when there is no tag.
remote_tag_commit() {
  local tag="$1" lines
  lines="$(git ls-remote --tags origin "refs/tags/${tag}" "refs/tags/${tag}^{}")" || return 2
  [ -n "$lines" ] || return 1
  # An annotated tag lists its object and then the peeled commit (^{}).
  if printf '%s\n' "$lines" | grep -q '\^{}$'; then
    printf '%s\n' "$lines" | awk '/\^\{\}$/ { print $1; exit }'
  else
    printf '%s\n' "$lines" | awk '{ print $1; exit }'
  fi
}
