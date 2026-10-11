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
- `stable releases are kept and drafts and prereleases are excluded` (chave `prerelease`)
- `highest stable version wins when several exist`
- `versionCode is derived from the tag using major*10000+minor*100+patch`
- `strict stable tag format rejects pre-release suffixes`
- `malformed tag is rejected with a typed error`
- `missing apk asset is a typed error`
- `multiple apk assets are a typed error`
- `apk asset is matched by file name and android package content type`
- `versionCode overflow is rejected`
- `invalid json is rejected`
- `no stable release returns no stable`
- `highest valid wins even when a lower release is malformed`

Testes (`UpdateCheckerTest`):
- `newer remote version offers an update`
- `equal version offers no update`
- `older remote version offers no update (never downgrade)`
- `version name mismatch with higher code still offers update`
- `null remote offers no update`

GREEN: implementar parser (filtro stable + derivação de versão + asset) e checker
(comparação por `versionCode`). Commit: `feat(app): GitHub release parser + update checker (TDD)`.

## Tarefa 2 — GitHubReleaseSource (Ktor, offline/429/ETag)

Arquivos:
- `app/src/main/kotlin/app/tcg/app/update/GitHubReleaseSource.kt`
- `app/src/test/kotlin/app/tcg/app/update/GitHubReleaseSourceTest.kt`

RED: `GitHubReleaseSource` ausente.

Testes (MockEngine):
- `fetch hits the public releases endpoint with accept and user agent and no token`
- `fetch maps a 200 body to Fetched with the etag`
- `malformed json maps to a typed failure`
- `offline network failure maps to Offline`
- `rate limit 429 with reset header maps to RateLimited`
- `etag 304 returns NotModified carrying the cached body`
- `no conditional header is sent before a body has been cached`

Decisão de cache: a fonte guarda `ETag` + corpo juntos, em memória (vida do
processo), e só envia `If-None-Match` quando já tem corpo — assim um `304`
devolve o corpo em `NotModified.cachedReleasesJson` e nunca deixa o chamador
sem metadados. Persistência em disco (se vier) deve guardar corpo junto ao
ETag; nunca um ETag órfão.

Commit: `feat(app): GitHub release metadata source with ETag and rate-limit handling (TDD)`.

## Tarefa 3 — ApkDownloader (integridade) + ApkValidator (puro)

Arquivos:
- `app/src/main/kotlin/app/tcg/app/update/ApkDownloader.kt`
- `app/src/main/kotlin/app/tcg/app/update/ApkValidator.kt`
- `app/src/test/kotlin/app/tcg/app/update/ApkValidatorTest.kt`
- `app/src/test/kotlin/app/tcg/app/update/ApkDownloaderTest.kt`

RED: `ApkValidator`/`ApkDownloader` ausentes.

Testes (`ApkValidatorTest`):
- `wrong application id is rejected`
- `lower or equal version code is rejected (no downgrade)`
- `signature mismatch is rejected`
- `missing archive signature digest is rejected`
- `matching package version and signature is accepted`

Testes (`ApkDownloaderTest`, MockEngine + diretório temporário):
- `https asset downloads and reports size and sha256`
- `http url is rejected as invalid`
- `download sends no authorization or api headers`
- `http error status is rejected and leaves no file`
- `size mismatch is rejected and removes the partial`
- `sha256 mismatch is rejected and removes the partial`
- `oversized body is bounded and removes the partial`

Integridade/segurança do download: URL precisa ser `https`; nenhuma
`Authorization`/cabeçalho de API é enviada (nada vazа para o host de redirect,
que pode ser `githubusercontent.com`); tamanho máximo limita disco; tamanho
exato + SHA-256 conferidos quando presentes; qualquer não-sucesso (HTTP,
mismatch, oversized, cancelamento) apaga o arquivo parcial via `finally`.
A autoridade de instalar continua com o `PackageInstaller` do Android. Commit:
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
Essa permissão de origem não substitui Android developer verification.
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

### Gate externo antes de release pública

- No Brasil, verificar regra vigente de Android developer verification antes de
  distribuição pública em dispositivos certificados.
- Para distribuição ampla: titular conclui identidade no Android Developer
  Console (ou Play Console se aplicável) e registra `app.tcg.app` + fingerprint
  SHA-256 do certificado de release. Só declarar release pronta após confirmar
  status Registered.
- Full Distribution tem taxa documentada de US$25 e exige verificação de
  identidade. Limited Distribution é gratuita, mas limita a 20 aparelhos
  explicitamente autorizados; adequada para teste pessoal, não release pública.
- Não pagar, criar conta nem enviar documentos em nome do titular. São ações
  externas com cobrança/dados pessoais; preparar instruções e continuar código
  enquanto aguardam ação do titular.
- Registro não garante ausência de avisos do Play Protect.
- Fontes oficiais: https://developer.android.com/developer-verification/guides,
  https://developer.android.com/developer-verification/guides/limited-distribution,
  https://developer.android.com/developer-verification/guides/full-distribution,
  https://developer.android.com/developer-verification/guides/faq.

## Não faz parte (ainda)

Publicar tag/release, PR para main, ou instalar em aparelho pessoal. O OTA é
adicional ao sync; sync real (migração/RLS/RPC + roundtrip entre dois clientes)
segue pendente e bloqueado por credencial de banco indisponível.
