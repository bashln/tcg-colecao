# Ambiente de desenvolvimento

Registro do que está instalado e do que o build precisa. Verificado em
2026-10-09.

## Presente

- Java 17 via mise em `JAVA_HOME`. Compatível com AGP 8.13.
- Android SDK em `/home/leonamduarte/Android/Sdk`.
  - `platforms/android-35`.
  - `build-tools/34.0.0` e `35.0.0`.
  - `platform-tools` com `adb` no PATH.
  - Licença `android-sdk-license` aceita.
- Gradle 8.14.6 via mise e o Gradle Wrapper do projeto.
- `local.properties` aponta `sdk.dir` para o SDK. Não versionado.

## Ausente, sem bloquear o build

- `cmdline-tools`, logo sem `sdkmanager` e `avdmanager`.
- Pacote `emulator` e nenhum AVD.
- `kotlinc` global. O plugin Gradle do Kotlin resolve.

## Versões escolhidas

| Peça | Versão | Motivo |
| --- | --- | --- |
| Gradle | 8.14.6 | Estável. Evita a linha 9. |
| AGP | 8.13.2 | Última estável da linha 8. Evita o AGP 9 alpha. |
| Kotlin | 2.0.21 | Embutido no Gradle. O plugin do projeto fixa a versão. |
| compileSdk | 35 | Já instalado. |

## Execução

- Testes do `:core` e build rodam em JVM com `./gradlew`.
- Para rodar o app com câmera, usar celular físico por `adb`. Câmera de
  emulador é falsa e o ML Kit fica irreal.
- Se um dia precisar de emulador, instalar `cmdline-tools` e depois
  `sdkmanager "emulator" "system-images;android-35;google_apis;x86_64"`.
