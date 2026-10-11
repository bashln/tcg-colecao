#!/usr/bin/env bash
set -uo pipefail

script="$(dirname "$0")/verify-release-signature.sh"
expected="d31a9c98580212663993b2ee7ea757e05fa284ccd2b54ad3b38e6b92a4c3f88f"
valid="Signer #1 certificate SHA-256 digest: $expected"
pass=0
fail=0

ok() { echo "ok   - $1"; pass=$((pass + 1)); }
bad() { echo "FAIL - $1"; fail=$((fail + 1)); }

expect_ok() {
  local desc="$1" certs="$2" fingerprint="$3"
  if printf '%s\n' "$certs" | "$script" "$fingerprint" >/dev/null 2>&1; then
    ok "$desc"
  else
    bad "$desc (esperava sucesso)"
  fi
}

expect_fail() {
  local desc="$1" certs="$2" fingerprint="$3"
  if printf '%s\n' "$certs" | "$script" "$fingerprint" >/dev/null 2>&1; then
    bad "$desc (esperava falha)"
  else
    ok "$desc"
  fi
}

expect_ok "certificado da release anterior é aceito" "$valid" "$expected"
expect_ok "comparação do fingerprint ignora caixa" "Signer #1 certificate SHA-256 digest: ${expected^^}" "$expected"
expect_fail "mesmo CN com chave diferente é recusado" "Signer #1 certificate DN: CN=TCG Colecao
Signer #1 certificate SHA-256 digest: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" "$expected"
expect_fail "certificado ausente é recusado" "No signers found" "$expected"
expect_fail "assinatura com múltiplos signers é recusada" "$valid
Signer #2 certificate SHA-256 digest: $expected" "$expected"
expect_fail "fingerprint esperado malformado é recusado" "$valid" "not-a-digest"

echo "----"
echo "pass=$pass fail=$fail"
(( fail == 0 ))
