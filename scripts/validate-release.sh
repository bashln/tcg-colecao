#!/usr/bin/env bash
# Validate stable and Alpha tag/version/asset contracts before publication.
# Usage: validate-release.sh TAG APK_NAME [EXISTING_TAG ...]
set -euo pipefail

err() { echo "release-guard: $*" >&2; exit 1; }

tag="${1:?usage: validate-release.sh TAG APK_NAME [EXISTING_TAG ...]}"
apk_file="${2:?usage: validate-release.sh TAG APK_NAME [EXISTING_TAG ...]}"
shift 2
existing=("$@")

version_code_for_tag() {
  local candidate="$1" major minor patch alpha target
  if [[ "$candidate" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
    major="${BASH_REMATCH[1]}"
    minor="${BASH_REMATCH[2]}"
    patch="${BASH_REMATCH[3]}"
    (( ${#major} <= 6 && ${#minor} <= 2 && ${#patch} <= 2 )) || return 1
    (( 10#$minor <= 99 && 10#$patch <= 99 )) || return 1
    REPLY=$(( 10#$major * 10000 + 10#$minor * 100 + 10#$patch ))
  elif [[ "$candidate" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.0-alpha\.([1-9][0-9]?)$ ]]; then
    major="${BASH_REMATCH[1]}"
    minor="${BASH_REMATCH[2]}"
    alpha="${BASH_REMATCH[3]}"
    (( ${#major} <= 6 && ${#minor} <= 2 && 10#$minor <= 99 )) || return 1
    target=$(( 10#$major * 10000 + 10#$minor * 100 ))
    (( target >= 100 && 10#$alpha <= 99 )) || return 1
    REPLY=$(( target - 100 + 10#$alpha ))
  else
    return 1
  fi
  (( REPLY >= 1 && REPLY <= 2100000000 )) || return 1
}

if ! version_code_for_tag "$tag"; then
  err "tag '$tag' must be stable vMAJOR.MINOR.PATCH or Alpha vMAJOR.MINOR.0-alpha.N"
fi
version_code=$REPLY
expected_apk="tcg-colecao-${tag}.apk"
[[ "$apk_file" == "$expected_apk" ]] || err "APK name '$apk_file' differs from expected '$expected_apk'"

max_existing=0
for existing_tag in "${existing[@]:-}"; do
  [[ -n "$existing_tag" ]] || continue
  if version_code_for_tag "$existing_tag"; then
    existing_code=$REPLY
    (( existing_code > max_existing )) && max_existing=$existing_code
  fi
done
(( version_code > max_existing )) || err "versionCode $version_code is not greater than published maximum $max_existing"

echo "$version_code"
