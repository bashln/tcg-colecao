#!/usr/bin/env bash
set -euo pipefail

expected="${1:?usage: verify-release-signature.sh EXPECTED_SHA256 < apksigner-output}"
if [[ ! "$expected" =~ ^[[:xdigit:]]{64}$ ]]; then
  echo "release-signature: expected certificate fingerprint must be 64 hex characters" >&2
  exit 1
fi

mapfile -t certificates < <(
  sed -nE 's/^(Signer #[0-9]+ |V[1-4] Signer: )certificate SHA-256 digest: ([[:xdigit:]]{64})$/\2/p' \
    | tr '[:upper:]' '[:lower:]' \
    | sort -u
)

if (( ${#certificates[@]} != 1 )); then
  echo "release-signature: expected exactly one distinct SHA-256 signer certificate" >&2
  exit 1
fi

if [[ "${certificates[0]}" != "${expected,,}" ]]; then
  echo "release-signature: APK certificate does not match the pinned release key" >&2
  exit 1
fi
