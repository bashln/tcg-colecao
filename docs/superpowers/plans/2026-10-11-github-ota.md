# OTA pelo GitHub Releases — plano de implementação

Pré-requisito: `docs/superpowers/specs/2026-10-11-github-ota-design.md`.
Cada tarefa é um commit verificado no branch `work/collection-foundation-2026-10-11`.
Comando de teste focado: `./gradlew :app:testDebugUnitTest --tests "app.tcg.app.update.*"`.
Suíte completa por tarefa: `./gradlew :app:testDebugUnitTest :app:assembleDebug`.

Nenhum token no app. Reusa Ktor já presente. Sem dependência nova de terceiros.

## Tarefa 1 — Modelo + ReleaseParser + UpdateChecker (puro)

Arquivos:
- `app/src/main/kotlin/app/tcg/app/update/UpdateModels.kt` (`GitHubRelease`, resultado do parser/checker)
- `app/src/main/kotlin/app/tcg/app/update/ReleaseParser.kt`
- `app/src/main/kotlin/app/tcg/app/update/UpdateChecker.kt`
- `app/src/test/kotlin/app/tcg/app/update/ReleaseParserTest.kt`
- `app/src/test/kotlin/app/tcg/app/update/UpdateCheckerTest.kt`

RED: escrever testes; `ReleaseParser`/`UpdateChecker` ausentes ⇒ `Unresolved reference`.

Testes (`ReleaseParserTest`):
- `stable releases are kept and drafts and prereleases are excluded`
- `highest stable version wins when several exist`
- `versionCode is derived from the tag using major*10000+minor*100+patch`
- `malformed tag is rejected with a typed error`
- `missing apk asset is a typed error`
- `apk asset is matched by file name and android package content type`

Testes (`UpdateCheckerTest`):
- `newer remote version offers an update`
- `equal version offers no update`
- `older remote version offers no update (never downgrade)`
- `version name mismatch with higher code still offers update`

GREEN: implementar parser (filtro stable + derivação de versão + asset) e checker
(comparação por `versionCode`). Commit: `feat(app): GitHub release parser + update checker (TDD)`.

## Tarefa 2 — GitHubReleaseSource (Ktor, offline/429/ETag)

Arquivos:
- `app/src/main/kotlin/app/tcg/app/update/GitHubReleaseSource.kt`
- `app/src/test/kotlin/app/tcg/app/update/GitHubReleaseSourceTest.kt`

RED: `GitHubReleaseSource` ausente.

Testes (MockEngine):
- `fetch parses a releases page into the parser input`
- `offline network failure maps to Offline`
- `rate limit 429 with reset header maps to RateLimited`
- `etag 304 returns NotModified without a body`
- `malformed json maps to a typed error`
- `no token is sent and the user agent is set`

Commit: `feat(app): GitHub release metadata source with ETag and rate-limit handling (TDD)`.

## Tarefa 3 — ApkDownloader (integridade) + ApkValidator (puro)

Arquivos:
- `app/src/main/kotlin/app/tcg/app/update/ApkDownloader.kt`
- `app/src/main/kotlin/app/tcg/app/update/ApkValidator.kt`
- `app/src/test/kotlin/app/tcg/app/update/ApkValidatorTest.kt`

RED: `ApkValidator` ausente.

Testes (`ApkValidatorTest`):
- `wrong application id is rejected`
- `lower or equal version code is rejected (no downgrade)`
- `signature mismatch is rejected`
- `matching package version and signature is accepted`

Download (integridade, no `ApkDownloader`/coordinator): tamanho diferente do
`apkSizeBytes` ou SHA-256 diferente ⇒ erro + apaga o arquivo parcial
(`partial or corrupt download is rejected and removed`). Commit:
`feat(app): APK download integrity + install pre-check validator (TDD)`.

## Tarefa 4 — UpdateCoordinator (máquina de estados)

Arquivos:
- `app/src/main/kotlin/app/tcg/app/update/UpdateCoordinator.kt`
- `app/src/test/kotlin/app/tcg/app/update/UpdateCoordinatorTest.kt`

RED: `UpdateCoordinator` ausente.

Testes:
- `check with no newer release reports no update`
- `check with newer release becomes ready with notes`
- `download reports progress then becomes install-ready`
- `cancelled download returns to ready without installing`
- `offline check surfaces offline and keeps the app usable`
- `invalid package surfaces incompatible and keeps data`
- `install hand-off is user initiated (no silent install)`

Commit: `feat(app): update coordinator state machine (TDD)`.

## Tarefa 5 — Instalador de plataforma + UI + wiring

Arquivos:
- `app/src/main/kotlin/app/tcg/app/update/UpdateInstaller.kt` (FileProvider + `ACTION_VIEW`, pre-check `PackageManager`)
- `app/src/main/AndroidManifest.xml` (`REQUEST_INSTALL_PACKAGES`, provider FileProvider)
- `app/src/main/res/xml/file_paths.xml`
- `app/src/main/res/values/strings.xml` (`update_*` pt-BR)
- `app/src/main/kotlin/app/tcg/app/ui/update/` (entrada em Perfil + estados)
- `app/src/main/kotlin/app/tcg/app/di/AppModule.kt` (providers)
- `app/src/test/kotlin/app/tcg/app/ui/update/UpdateViewModelTest.kt`

Regras: `canRequestPackageInstalls()` falso ⇒ diálogo + `ACTION_MANAGE_UNKNOWN_APP_SOURCES`.
Dispara `ACTION_VIEW` com `FLAG_GRANT_READ_URI_PERMISSION`; **nunca** instala silenciosamente,
**nunca** suprime Play Protect. Copy honesto (ver spec). Teste do ViewModel cobre
estados loading/offline/ready/error. Commit:
`feat(app): GitHub OTA installer and update UI (TDD)`.

## Tarefa 6 — Docs + salvaguarda do workflow de release

Arquivos:
- `docs/releasing.md` (seção "Atualização em app" — Obtainium continua + OTA in-app)
- `.github/workflows/release.yml` (asserção extra: nome do asset `tcg-colecao-v<versão>.apk`
  e `versionCode` não-decrescente; mantém falha fechada de assinatura)

Commit: `docs+ci: document in-app OTA and assert release asset/version contract`.

## Ordem e gates

1 → 2 → 3 → 4 → 5 → 6. Toda tarefa: RED observado, GREEN, suíte completa verde,
CI (android+web) verde no push, uma linha em `.audit/supabase-auto-sync.tsv`,
`progress.json` e `errors.json`.

## Não faz parte (ainda)

Publicar tag/release, PR para main, ou instalar em aparelho pessoal. O OTA é
adicional ao sync; sync real (migração/RLS/RPC + roundtrip entre dois clientes)
segue pendente e bloqueado por credencial de banco indisponível.
