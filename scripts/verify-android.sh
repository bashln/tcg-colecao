#!/usr/bin/env bash
# Driver de verificação do APK Android. Lê a hierarquia da UI com uiautomator, aciona a tela
# por texto e guarda a evidência em .audit/verify/<run>/. Nunca desinstala o app: instalar com
# -r preserva a coleção local, e desinstalar apagaria dados de quem usa o aparelho.
#
# Uso:
#   scripts/verify-android.sh doctor
#   scripts/verify-android.sh install
#   scripts/verify-android.sh launch
#   scripts/verify-android.sh wait "Início" [segundos]
#   scripts/verify-android.sh tap "Perfil"
#   scripts/verify-android.sh find "Perfil" [--file du.xml]
#   scripts/verify-android.sh texts [--file du.xml]
#   scripts/verify-android.sh dump
#   scripts/verify-android.sh shot nome
#   scripts/verify-android.sh evidence
#
# Códigos de saída: 0 ok, 1 texto não encontrado, 2 adb ausente, 3 aparelho ausente ou ambíguo,
# 4 app não instalado, 5 tela bloqueada ou apagada, 6 consentimento para usar o aparelho ausente.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
package_name="app.tcg.app"
activity_name=".MainActivity"
apk="${VERIFY_APK:-$root/app/build/outputs/apk/debug/app-debug.apk}"
run_dir="${VERIFY_RUN_DIR:-$root/.audit/verify/$(date -u +%Y%m%dT%H%M%SZ)}"

die() {
    local code=$1
    shift
    echo "verify-android: $*" >&2
    exit "$code"
}

resolve_adb() {
    if [[ -n "${VERIFY_ADB:-}" ]]; then
        echo "$VERIFY_ADB"
        return 0
    fi
    if [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
        echo "$ANDROID_HOME/platform-tools/adb"
        return 0
    fi
    if [[ -n "${ANDROID_SDK_ROOT:-}" && -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]]; then
        echo "$ANDROID_SDK_ROOT/platform-tools/adb"
        return 0
    fi
    local sdk_dir=""
    if [[ -f "$root/local.properties" ]]; then
        sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" | tail -n1)"
    fi
    if [[ -n "$sdk_dir" && -x "$sdk_dir/platform-tools/adb" ]]; then
        echo "$sdk_dir/platform-tools/adb"
        return 0
    fi
    command -v adb
}

adb_path=""

adb() {
    if [[ -z "$adb_path" ]]; then
        adb_path="$(resolve_adb)" || die 2 "adb não encontrado. Defina ANDROID_HOME ou instale platform-tools."
        [[ -x "$adb_path" ]] || die 2 "adb não executável em $adb_path"
    fi
    "$adb_path" "$@"
}

# O aparelho conectado pode ter a coleção pessoal de alguém. Instalar, tocar na tela e trocar
# o estado da rede só acontecem com confirmação explícita de que o aparelho é de verificação.
require_device_consent() {
    [[ "${VERIFY_DEVICE_OK:-}" == "1" ]] || die 6 \
        "operação no aparelho exige VERIFY_DEVICE_OK=1. Use um aparelho de verificação, nunca um com a coleção de outra pessoa."
}

ui_lookup() { # mode file needle
    python3 - "$1" "$2" "${3:-}" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

mode, path, needle = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    root = ET.parse(path).getroot()
except (OSError, ET.ParseError) as error:
    sys.exit(f"verify-android: dump ilegível em {path}: {error}")

def labelled():
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        desc = (node.get("content-desc") or "").strip()
        if text or desc:
            yield node, text, desc

if mode == "texts":
    for _, text, desc in labelled():
        print(text or desc)
    sys.exit(0)

exact, partial = [], []
for node, text, desc in labelled():
    for value in (text, desc):
        if not value:
            continue
        if value == needle:
            exact.append(node)
        elif needle in value:
            partial.append(node)

matches = exact or partial
if not matches:
    sys.exit(1)

bounds = matches[0].get("bounds", "")
match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
if not match:
    sys.exit(f"verify-android: bounds inválidos '{bounds}' no nó de '{needle}'")

x1, y1, x2, y2 = (int(value) for value in match.groups())
print((x1 + x2) // 2, (y1 + y2) // 2)
PY
}

new_dump_file() {
    mkdir -p "$run_dir"
    echo "$run_dir/ui-$(date -u +%H%M%S%N).xml"
}

live_dump() {
    local file
    file="$(new_dump_file)"
    adb shell uiautomator dump /sdcard/tcg-verify-ui.xml >/dev/null
    adb exec-out cat /sdcard/tcg-verify-ui.xml >"$file"
    printf '%s\n' "$file"
}

cmd_doctor() {
    require_device_consent
    local devices count
    devices="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
    count="$(printf '%s' "$devices" | grep -c . || true)"
    if [[ "$count" -ne 1 ]]; then
        die 3 "verificação Android exige exatamente um aparelho conectado; encontrados: $count."
    fi
    if ! adb shell pm list packages "$package_name" | grep -qx "package:$package_name"; then
        die 4 "$package_name não está instalado. Rode: scripts/verify-android.sh install"
    fi
    if ! adb shell dumpsys power | grep -q "mWakefulness=Awake"; then
        die 5 "tela apagada ou bloqueada. Desbloqueie o aparelho e repita."
    fi
    echo "verify-android: aparelho $devices, $package_name instalado, tela ativa"
    echo "verify-android: evidência em $run_dir"
}

cmd_install() {
    require_device_consent
    [[ -f "$apk" ]] || die 4 "APK ausente em $apk. Rode ./gradlew :app:assembleDebug ou defina VERIFY_APK"
    adb install -r "$apk" >/dev/null
    echo "verify-android: instalado com -r (dados locais preservados)"
}

cmd_launch() {
    require_device_consent
    adb shell am start -W -n "$package_name/$activity_name" >/dev/null
    echo "verify-android: $package_name iniciado"
}

cmd_dump() {
    require_device_consent
    live_dump
}

cmd_find() {
    local needle="${1:?uso: find TEXTO [--file dump.xml]}" file=""
    shift
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --file) file="${2:?--file precisa de caminho}"; shift 2 ;;
            *) die 1 "argumento desconhecido: $1" ;;
        esac
    done
    if [[ -z "$file" ]]; then
        require_device_consent
        file="$(live_dump)"
    fi
    ui_lookup find "$file" "$needle" || die 1 "texto '$needle' não encontrado na tela"
}

cmd_texts() {
    local file=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --file) file="${2:?--file precisa de caminho}"; shift 2 ;;
            *) die 1 "argumento desconhecido: $1" ;;
        esac
    done
    if [[ -z "$file" ]]; then
        require_device_consent
        file="$(live_dump)"
    fi
    ui_lookup texts "$file" ""
}

cmd_wait() {
    require_device_consent
    local needle="${1:?uso: wait TEXTO [segundos]}" timeout="${2:-20}" waited=0 file
    while (( waited < timeout )); do
        file="$(live_dump)"
        if ui_lookup find "$file" "$needle" >/dev/null; then
            echo "verify-android: '$needle' visível após ${waited}s"
            return 0
        fi
        sleep 1
        waited=$(( waited + 1 ))
    done
    die 1 "texto '$needle' não apareceu em ${timeout}s"
}

cmd_tap() {
    require_device_consent
    local needle="${1:?uso: tap TEXTO}" coords x y
    coords="$(cmd_find "$needle")"
    x="${coords%% *}"
    y="${coords##* }"
    adb shell input tap "$x" "$y"
    echo "verify-android: toque em '$needle' em $x,$y"
}

cmd_shot() {
    require_device_consent
    local name="${1:?uso: shot nome}" file
    mkdir -p "$run_dir"
    file="$run_dir/$name.png"
    adb exec-out screencap -p >"$file"
    echo "$file"
}

cmd_evidence() {
    mkdir -p "$run_dir"
    echo "$run_dir"
}

command="${1:?uso: verify-android.sh doctor|install|launch|dump|find|wait|tap|texts|shot|evidence}"
shift

case "$command" in
    doctor) cmd_doctor "$@" ;;
    install) cmd_install "$@" ;;
    launch) cmd_launch "$@" ;;
    dump) cmd_dump "$@" ;;
    find) cmd_find "$@" ;;
    wait) cmd_wait "$@" ;;
    tap) cmd_tap "$@" ;;
    texts) cmd_texts "$@" ;;
    shot) cmd_shot "$@" ;;
    evidence) cmd_evidence "$@" ;;
    *) die 1 "comando desconhecido: $command" ;;
esac
