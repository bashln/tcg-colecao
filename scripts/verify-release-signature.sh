#!/usr/bin/env bash
set -euo pipefail

expected="${1:?usage: verify-release-signature.sh EXPECTED_SHA256 < apksigner-output}"
if [[ ! "$expected" =~ ^[[:xdigit:]]{64}$ ]]; then
  echo "release-signature: expected certificate fingerprint must be 64 hex characters" >&2
  exit 1
fi

mapfile -t signers < <(
  sed -nE 's/^Signer #([0-9]+) certificate SHA-256 digest: ([[:xdigit:]]{64})$/\1:\2/p'
)

if (( ${#signers[@]} != 1 )); then
  echo "release-signature: expected exactly one SHA-256 signer certificate" >&2
  exit 1
fi

signer="${signers[0]%%:*}"
actual="${signers[0]#*:}"
if [[ "$signer" != "1" || "${actual,,}" != "${expected,,}" ]]; then
  echo "release-signature: APK certificate does not match the pinned release key" >&2
  exit 1
fi
