#!/usr/bin/env bash
# Prova que o driver Android falha do jeito certo sem aparelho e que a leitura da UI
# acerta os nós. Roda no JVM da máquina, sem adb real.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
driver="$root/scripts/verify-android.sh"
fixture="$root/scripts/fixtures/android-ui-dump.xml"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

failures=0
check() { # descrição esperado obtido
    if [[ "$2" == "$3" ]]; then
        echo "ok - $1"
    else
        echo "not ok - $1 (esperado: $2, obtido: $3)"
        failures=$(( failures + 1 ))
    fi
}

run() { # captura saída e código
    set +e
    output="$("$@" 2>&1)"
    code=$?
    set -e
}

cat >"$tmp/adb" <<'STUB'
#!/usr/bin/env bash
case "$1" in
    devices)
        printf 'List of devices attached\n'
        printf '%b' "${STUB_DEVICES:-}"
        ;;
    shell)
        case "$*" in
            "shell pm list packages app.tcg.app") printf 'package:app.tcg.app\n' ;;
            "shell dumpsys power") printf 'mWakefulness=Awake\n' ;;
            *) exit 0 ;;
        esac
        ;;
esac
STUB
chmod +x "$tmp/adb"

run "$driver" find "Perfil" --file "$fixture"
check "find por texto devolve o centro do nó" "0 108 2300" "$code $output"

run "$driver" find "Escanear carta" --file "$fixture"
check "find por content-desc devolve o centro do nó" "0 550 1900" "$code $output"

run "$driver" find "Coleção" --file "$fixture"
check "find aceita correspondência parcial" "0 270 130" "$code $output"

run "$driver" find "Carta ausente" --file "$fixture"
check "find falha quando o texto não existe" "1" "$code"

run "$driver" texts --file "$fixture"
check "texts sai com 0" "0" "$code"
if [[ "$output" == *"TCG Coleção"* && "$output" == *"Perfil"* && "$output" == *"Escanear carta"* ]]; then
    check "texts inclui texto e content-desc" "ok" "ok"
else
    check "texts inclui texto e content-desc" "ok" "saída: $output"
fi

run env VERIFY_ADB="$tmp/adb" STUB_DEVICES='FAKE123\tdevice\n' "$driver" doctor
check "doctor sem consentimento falha com o código 6" "6" "$code"
[[ "$output" == *"VERIFY_DEVICE_OK=1"* ]] \
    && check "doctor sem consentimento diz o que fazer" "ok" "ok" \
    || check "doctor sem consentimento diz o que fazer" "ok" "$output"

run env VERIFY_DEVICE_OK=1 VERIFY_ADB="$tmp/adb" STUB_DEVICES='' "$driver" doctor
check "doctor sem aparelho falha com o código 3" "3" "$code"
[[ "$output" == *"exatamente um aparelho"* ]] \
    && check "doctor sem aparelho explica o motivo" "ok" "ok" \
    || check "doctor sem aparelho explica o motivo" "ok" "$output"

run env VERIFY_DEVICE_OK=1 VERIFY_ADB="$tmp/adb" STUB_DEVICES='FAKE123\tdevice\n' "$driver" doctor
check "doctor com um aparelho e app instalado passa" "0" "$code"

run env VERIFY_DEVICE_OK=1 VERIFY_ADB="$tmp/adb" STUB_DEVICES='A\tdevice\nB\tdevice\n' "$driver" doctor
check "doctor recusa dois aparelhos" "3" "$code"

run env VERIFY_DEVICE_OK=1 VERIFY_ADB="$tmp/adb" STUB_DEVICES='FAKE123\tunauthorized\n' "$driver" doctor
check "doctor recusa aparelho não autorizado" "3" "$code"

run env VERIFY_DEVICE_OK=1 VERIFY_ADB="$tmp/adb" ANDROID_HOME="" ANDROID_SDK_ROOT="" "$driver" find "Perfil" --file "$fixture"
check "find não exige aparelho quando recebe --file" "0" "$code"

run env VERIFY_DEVICE_OK=1 VERIFY_ADB="$tmp/sem-adb" "$driver" doctor
check "doctor falha com o código 2 quando adb não existe" "2" "$code"

run "$driver" comando-invalido
check "comando desconhecido falha com o código 1" "1" "$code"

if (( failures > 0 )); then
    echo "verify-android.test: $failures falha(s)" >&2
    exit 1
fi
echo "verify-android.test: todos os casos passaram"
