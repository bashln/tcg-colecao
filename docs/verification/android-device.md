# Android no aparelho

O app Android roda em um aparelho conectado por ADB. Este guia cobre como dirigi-lo por texto na tela e como guardar a evidência. Ele complementa os gates de JVM, que não instalam nada.

## Antes de começar

`scripts/verify-android.sh` precisa de `adb` e de `python3`. Ele procura o `adb` em `ANDROID_HOME`, em `ANDROID_SDK_ROOT`, em `sdk.dir` do `local.properties` e no `PATH`.

Toda operação que toca o aparelho exige `VERIFY_DEVICE_OK=1`. O aparelho conectado pode conter a coleção de outra pessoa, e o driver não tem como saber. Use um aparelho de verificação.

```sh
export VERIFY_DEVICE_OK=1
export VERIFY_RUN_DIR="$PWD/.audit/verify/$(date -u +%Y%m%dT%H%M%SZ)"
./scripts/verify-android.sh doctor
```

O `doctor` confere quatro coisas e falha com um código próprio:

| Código | Significado |
| --- | --- |
| 2 | `adb` ausente ou não executável |
| 3 | nenhum aparelho, mais de um, ou aparelho não autorizado |
| 4 | `app.tcg.app` não instalado, ou APK de debug ausente |
| 5 | tela apagada ou bloqueada |
| 6 | `VERIFY_DEVICE_OK=1` ausente |

## Instalar e abrir

```sh
./gradlew :app:assembleDebug
./scripts/verify-android.sh install
./scripts/verify-android.sh launch
./scripts/verify-android.sh wait "Início"
```

Builds de debug usam o sufixo `.debug`, então convivem com o APK publicado no mesmo aparelho. Para dirigir esse build, defina `VERIFY_PACKAGE=app.tcg.app.debug`. `install` usa `adb install -r`, que preserva os dados locais. O driver nunca desinstala: desinstalar apagaria a coleção do aparelho. Por padrão ele instala o APK de debug. Para verificar o APK publicado, aponte `VERIFY_APK` para o arquivo baixado da release.

## Dirigir por texto

Os comandos leem a hierarquia da tela com `uiautomator` e procuram o texto ou o `content-desc` exato. Sem correspondência exata, aceitam correspondência parcial.

```sh
./scripts/verify-android.sh tap "Coleção"
./scripts/verify-android.sh wait "Buscar na coleção"
./scripts/verify-android.sh texts
./scripts/verify-android.sh shot colecao
```

`find`, `texts` e `wait` aceitam `--file` para ler um dump já salvo, o que permite testar o driver sem aparelho.

## Jornadas

Cada jornada termina com uma captura de tela no diretório de evidência.

- **Abrir o app.** `launch`, depois `wait "Início"`. A tela inicial mostra o valor da coleção ou o estado vazio com `Sua coleção está vazia`.
- **Coleção.** `tap "Coleção"`, depois `wait "Buscar na coleção"`. Digite um termo com `adb shell input text` e confira os itens com `texts`.
- **Backup.** `tap "Perfil"`, depois `wait "Cópia local e backup"`. Exportar e importar abrem o seletor de arquivos do sistema, então essa parte continua manual: confira o preview de importação com `shot` e compare as contagens.
- **Sem rede.** Desligue a rede com `adb shell cmd connectivity airplane-mode enable`, abra uma carta da coleção e confirme que os preços conhecidos continuam visíveis e que a falha aparece como estado explícito. Religue com `disable` no fim.
- **Câmera negada.** Negue a permissão de câmera nas configurações do app e abra `Escanear`. A busca manual precisa continuar alcançável.

## Evidência

Fica em `.audit/verify/<run>/`, com capturas `.png` e dumps `.xml`. O diretório não entra no git. Guarde o caminho no relatório do trabalho: uma captura sozinha não prova o fluxo inteiro, então registre também a saída dos comandos e o estado observado antes e depois.

## Resultado da verificação em aparelho

Executada em 13 de novembro de 2026 em um Galaxy A32 (SM-A325M) com Android 13, pela Meshnet, contra o APK publicado v0.7.0. A assinatura do APK baixado bateu com a impressão digital fixada, e `adb install -r` atualizou 0.6.0 para 0.7.0 sem apagar as 2 cartas da coleção.

- **Abertura.** Tela inicial com valor da coleção e o aviso de que o total é parcial.
- **Coleção.** A aba abre com `Buscar na coleção`.
- **Perfil.** Seção `Cópia local e backup` visível, com `Escolher pasta de backup` e `Importar cópia`.
- **Scan.** Com a câmera liberada, a tela mostra `Enquadre a carta` e as ações de galeria e captura.
- **Câmera negada.** A tela mostra `Permita o acesso à câmera para escanear` e mantém `Escolher da galeria` e `Buscar por nome`. A busca manual abre com o campo `Nome da carta`.
- **Falha de leitura.** A tela mostra `Não foi possível confirmar a carta pela foto`, com busca manual e diagnóstico, sem travar.
- **Atualização.** `Procurar atualização` responde `Você já está na versão mais recente (0.7.0)`.

O que esta execução não cobriu:

- **Sem rede.** Ativar o modo avião derruba a própria conexão ADB, que passa pela Meshnet. O estado offline continua provado apenas por teste de JVM.
- **Exportar e importar backup.** Abre o seletor de arquivos do sistema e exige escolher pasta e, se houver criptografia, digitar a senha. Fica como passo manual.

## Limites

- Negar permissão, desligar a rede e desbloquear a tela mexem no aparelho. Não faça isso em um aparelho com a coleção de outra pessoa.
- O `uiautomator` depende dos rótulos em português de `app/src/main/res/values/strings.xml`. Mudar um rótulo quebra a jornada, não o app.
- A suíte instrumentada continua sendo a prova de comportamento de Compose. O driver prova o caminho que uma pessoa faz.
- Pela Meshnet, a VPN comum do mesmo app precisa estar desligada. Com a VPN ativa, o aparelho respondia ping e recusava TCP nas portas de depuração e de pareamento. Com a VPN desligada e a Meshnet ligada, `adb connect` funcionou no mesmo minuto.

## Driver

`scripts/verify-android.test.sh` prova o driver sem aparelho em 16 casos, incluindo ausência de aparelho, aparelho não autorizado, dois aparelhos, falta de consentimento e leitura de nós por texto e por `content-desc`. Rode antes de confiar no driver depois de mudá-lo.

A suíte instrumentada completa roda com `-Pandroid.testInstrumentationRunnerArguments.notClass=app.tcg.app.scan.EndToEndRecognitionTest`. Essa classe de acurácia de OCR roda isolada, porque na suíte ela derruba o processo do runner ao terminar.
