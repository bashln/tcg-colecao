#!/usr/bin/env bash
set -euo pipefail

script="$(cd "$(dirname "$0")" && pwd)/verify-release-tag.sh"
test_tmp="${RUNNER_TEMP:-${TMPDIR:-/tmp/opencode}}"
mkdir -p "$test_tmp"
test_root="$(mktemp -d "$test_tmp/release-tag-check.XXXXXX")"
origin="$test_root/origin.git"
seed="$test_root/seed"
checkout="$test_root/checkout"

git init --bare --initial-branch=main "$origin" >/dev/null
git init --initial-branch=main "$seed" >/dev/null
git -C "$seed" config user.name "Release guard test"
git -C "$seed" config user.email "release-guard@example.invalid"
printf 'initial\n' > "$seed/source.txt"
git -C "$seed" add source.txt
git -C "$seed" commit -m initial >/dev/null
initial_commit="$(git -C "$seed" rev-parse HEAD)"
git -C "$seed" remote add origin "$origin"
git -C "$seed" push -u origin main >/dev/null
git -C "$seed" tag v9.0.0
git -C "$seed" push origin refs/tags/v9.0.0 >/dev/null
git clone "$origin" "$checkout" >/dev/null 2>&1

if (cd "$checkout" && "$script" v9.0.0) >/dev/null; then
  echo "ok   - published tag points to build commit"
else
  echo "FAIL - unchanged remote tag was rejected"
  exit 1
fi

printf 'retagged source\n' >> "$seed/source.txt"
git -C "$seed" commit -am retagged >/dev/null
git -C "$seed" push origin main >/dev/null
git -C "$seed" tag --force v9.0.0
git -C "$seed" push --force origin refs/tags/v9.0.0 >/dev/null

if (cd "$checkout" && "$script" v9.0.0) >/dev/null 2>&1; then
  echo "FAIL - moved remote tag was accepted for stale build commit"
  exit 1
else
  echo "ok   - moved remote tag rejected before publish"
fi

git -C "$seed" push origin :refs/tags/v9.0.0 >/dev/null
if (cd "$checkout" && "$script" v9.0.0) >/dev/null 2>&1; then
  echo "FAIL - deleted remote tag was accepted"
  exit 1
else
  echo "ok   - deleted remote tag rejected before publish"
fi

git -C "$seed" tag --force -a v9.0.0 -m annotated "$initial_commit"
git -C "$seed" push --force origin refs/tags/v9.0.0 >/dev/null
if (cd "$checkout" && "$script" v9.0.0) >/dev/null; then
  echo "ok   - annotated tag peeled to build commit"
else
  echo "FAIL - unchanged annotated tag was rejected"
  exit 1
fi

git -C "$seed" tag --force -a v9.0.0 -m "annotated retag" HEAD
git -C "$seed" push --force origin refs/tags/v9.0.0 >/dev/null
if (cd "$checkout" && "$script" v9.0.0) >/dev/null 2>&1; then
  echo "FAIL - moved annotated tag was accepted"
  exit 1
else
  echo "ok   - moved annotated tag rejected before publish"
fi
