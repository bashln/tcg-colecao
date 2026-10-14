#!/usr/bin/env bash
set -euo pipefail

tag="${1:?usage: verify-release-tag.sh TAG}"
tag_ref="refs/tags/$tag"
verification_ref=refs/verification/release-tag

if ! git check-ref-format "$tag_ref"; then
  echo "release-guard: invalid tag ref '$tag_ref'" >&2
  exit 1
fi

if ! git fetch --quiet --force --no-tags origin "$tag_ref:$verification_ref"; then
  echo "release-guard: tag '$tag' is no longer present on origin" >&2
  exit 1
fi

tag_commit="$(git rev-parse --verify "$verification_ref^{commit}")"
build_commit="$(git rev-parse --verify 'HEAD^{commit}')"
if [[ "$tag_commit" != "$build_commit" ]]; then
  echo "release-guard: origin tag '$tag' moved after this build started" >&2
  echo "release-guard: tag=$tag_commit build=$build_commit" >&2
  exit 1
fi
