#!/usr/bin/env bash
# Testes do guard de release (scripts/validate-release.sh). Roda local, sem segredos e sem
# mutação no GitHub. Sai com código != 0 se algum caso falhar.
set -uo pipefail

script="$(dirname "$0")/validate-release.sh"
pass=0
fail=0

ok()   { echo "ok   - $1"; pass=$((pass + 1)); }
bad()  { echo "FAIL - $1"; fail=$((fail + 1)); }

expect_ok() {
  local desc="$1"; shift
  if out="$("$script" "$@" 2>/dev/null)"; then ok "$desc"; else bad "$desc (esperava sucesso)"; fi
}

expect_fail() {
  local desc="$1"; shift
  if "$script" "$@" >/dev/null 2>&1; then bad "$desc (esperava falha)"; else ok "$desc"; fi
}

expect_code() {
  local desc="$1" expected="$2"; shift 2
  local out
  if out="$("$script" "$@" 2>/dev/null)" && [[ "$out" == "$expected" ]]; then
    ok "$desc"
  else
    bad "$desc (esperava versionCode $expected, obtido '${out:-<erro>}')"
  fi
}

# Caminho feliz e cálculo exato do versionCode (major*10000 + minor*100 + patch).
expect_code "versionCode de v1.2.3 é 10203" "10203" "v1.2.3" "tcg-colecao-v1.2.3.apk"
expect_ok "versão estável válida maior que as existentes" "v1.2.3" "tcg-colecao-v1.2.3.apk" "v1.0.0" "v1.2.0"
expect_ok "primeira release sem tags anteriores" "v0.1.0" "tcg-colecao-v0.1.0.apk"
expect_ok "minor e patch no limite 99" "v1.99.99" "tcg-colecao-v1.99.99.apk"

# Formato de tag estrito (estável, sem sufixo).
expect_fail "tag malformada" "release-5" "tcg-colecao-release-5.apk"
expect_fail "sufixo de prerelease" "v1.2.3-beta" "tcg-colecao-v1.2.3-beta.apk"
expect_fail "tag sem v" "1.2.3" "tcg-colecao-1.2.3.apk"

# Limites de componentes e overflow.
expect_fail "minor fora de 0..99" "v1.100.0" "tcg-colecao-v1.100.0.apk"
expect_fail "patch fora de 0..99" "v1.0.100" "tcg-colecao-v1.0.100.apk"
expect_fail "versionCode acima do limite Android" "v210001.0.0" "tcg-colecao-v210001.0.0.apk"

# Sem duplicata nem downgrade (versionCode deve ser estritamente maior que o máximo publicado).
expect_fail "versionCode duplicado" "v1.2.0" "tcg-colecao-v1.2.0.apk" "v1.2.3"
expect_fail "downgrade" "v1.0.0" "tcg-colecao-v1.0.0.apk" "v1.2.3"
expect_fail "igual ao máximo publicado" "v1.2.3" "tcg-colecao-v1.2.3.apk" "v1.2.3"

# Alphas can update existing installs but reserve a higher code than every published build.
expect_code "versionCode de v0.3.0-alpha.1 é 201" "201" "v0.3.0-alpha.1" "tcg-colecao-v0.3.0-alpha.1.apk" "v0.2.0"
expect_code "versionCode de v0.3.0-alpha.99 é 299" "299" "v0.3.0-alpha.99" "tcg-colecao-v0.3.0-alpha.99.apk" "v0.2.0"
expect_fail "tag Alpha com zero à esquerda é rejeitada" "v0.3.0-alpha.01" "tcg-colecao-v0.3.0-alpha.01.apk" "v0.2.0"
expect_fail "sequência Alpha acima de 99 é rejeitada" "v0.3.0-alpha.100" "tcg-colecao-v0.3.0-alpha.100.apk" "v0.2.0"
expect_fail "tag Alpha exige patch zero" "v0.3.1-alpha.1" "tcg-colecao-v0.3.1-alpha.1.apk" "v0.2.0"
expect_fail "tag Alpha exige versionCode positivo" "v0.0.0-alpha.1" "tcg-colecao-v0.0.0-alpha.1.apk" "v0.0.0"
expect_fail "segunda Alpha exige código maior que primeira" "v0.3.0-alpha.1" "tcg-colecao-v0.3.0-alpha.1.apk" "v0.3.0-alpha.1"
expect_fail "stable abaixo do Alpha publicado é rejeitada" "v0.2.1" "tcg-colecao-v0.2.1.apk" "v0.2.0" "v0.3.0-alpha.1"
expect_code "stable v0.3.0 atualiza Alpha v0.3.0-alpha.1" "300" "v0.3.0" "tcg-colecao-v0.3.0.apk" "v0.2.0" "v0.3.0-alpha.1"

# Contrato do nome do ativo.
expect_fail "nome de APK divergente" "v2.0.0" "tcg-colecao-v1.0.0.apk"

echo "----"
echo "pass=$pass fail=$fail"
(( fail == 0 ))
