#!/usr/bin/env bash
# Guard de release: valida o contrato tag/versão/ativo ANTES de publicar. Fail-closed.
# Uso: validate-release.sh TAG NOME_APK [TAG_ESTAVEL_EXISTENTE ...]
# Imprime o versionCode calculado (major*10000 + minor*100 + patch) no sucesso; sai != 0 em erro.
set -euo pipefail

err() { echo "release-guard: $*" >&2; exit 1; }

tag="${1:?uso: validate-release.sh TAG NOME_APK [TAG_EXISTENTE ...]}"
apk_file="${2:?uso: validate-release.sh TAG NOME_APK [TAG_EXISTENTE ...]}"
shift 2
existing=("$@")

# Tag estável exata: vMAJOR.MINOR.PATCH, sem sufixo algum (drafts/prereleases são filtrados no consumidor).
if [[ ! "$tag" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  err "tag '$tag' não é um tag estável exato vMAJOR.MINOR.PATCH (sem sufixo)"
fi
major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"

# minor/patch limitados a 0..99 no esquema major*10000 + minor*100 + patch.
(( 10#$minor <= 99 )) || err "minor fora do intervalo 0..99: $minor"
(( 10#$patch <= 99 )) || err "patch fora do intervalo 0..99: $patch"

# versionCode dentro do limite positivo do Android; rejeita overflow.
version_code=$(( 10#$major * 10000 + 10#$minor * 100 + 10#$patch ))
(( version_code <= 2100000000 )) || err "versionCode overflow: $version_code"
(( version_code >= 1 )) || err "versionCode inválido: $version_code"

# Contrato do nome do ativo.
expected_apk="tcg-colecao-${tag}.apk"
[[ "$apk_file" == "$expected_apk" ]] || err "nome do APK '$apk_file' difere do esperado '$expected_apk'"

# versionCode deve ser estritamente maior que o máximo já publicado (sem duplicata nem downgrade).
max_existing=0
for e in "${existing[@]:-}"; do
  [[ -z "$e" ]] && continue
  if [[ "$e" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    ec=$(( 10#${BASH_REMATCH[1]} * 10000 + 10#${BASH_REMATCH[2]} * 100 + 10#${BASH_REMATCH[3]} ))
    (( ec > max_existing )) && max_existing=$ec
  fi
done
(( version_code > max_existing )) || err "versionCode $version_code não é maior que o máximo publicado $max_existing (sem duplicata/downgrade)"

echo "$version_code"
