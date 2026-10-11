# OTA pelo GitHub Releases — design

Status: aprovado para implementação (autorização ampla do usuário).
Escopo: atualização opcional em app distribuído fora da Play Store, usando apenas
GitHub Releases como canal. Sem serviço de atualização de terceiros.

## Objetivo

Quando existir uma release mais nova no GitHub, o app oferece uma atualização
opcional com versão e notas da release, em um fluxo simples e honesto. A pessoa
escolhe quando instalar. O app **nunca** instala silenciosamente, **nunca** pede
permissão privilegiada e **nunca** suprime ou finge remover avisos do Play
Protect.

## Restrições não negociáveis

- **Canal único:** GitHub Releases (`api.github.com/repos/bashln/tcg-colecao/releases`).
- **Sem token:** requisições anônimas. Repo é público; a API de releases é pública.
- **HTTPS** para metadados e download do APK.
- **Somente stable:** ignora rascunhos (`draft`) e pré-releases (`prerelease`).
- **Mesmo `applicationId`** (`app.tcg.app`) e **mesma assinatura** exigidos.
  A imposição autoritativa é da plataforma (PackageInstaller); o app valida de
  forma defensiva antes de chamar o instalador.
- **`versionCode` não-decrescente:** sem downgrade. `versionCode` vem da tag
  (`major*10000 + minor*100 + patch`), igual ao `release.yml`.
- **Chave de assinatura imutável:** o workflow de release continua falhando
  fechado (keystore ausente ou `apksigner` não casando ⇒ nada publicado).
- **Play Protect:** pode exibir aviso para app sideloaded. **Isso não é removível
  nem prometemos remover.** O copy explica honestamente a origem (GitHub).
- **Instalação:** `PackageInstaller`/diálogo do sistema via `ACTION_VIEW` com
  FileProvider. Sem install silencioso, sem `REQUEST_INSTALL` privilegiado além
  do padrão "permitir apps desta fonte".

## Arquitetura (estreita)

Camada pura e testável + fina camada de plataforma. Reusa Ktor já presente.

```
ReleaseMetadataSource (interface)      -> GitHubReleaseSource (Ktor, ETag/429/offline)
        |  GitHubRelease?
ReleaseParser (puro)                   -> filtra stable, extrai asset APK, deriva versionCode
UpdateChecker (puro)                   -> UpdateAvailable | NoUpdate (compara versões)
ApkDownloader (Ktor -> cache)          -> valida tamanho/sha256 (defesa em profundidade)
ApkValidator (puro)                    -> applicationId/versionCode/assinatura corretos?
UpdateCoordinator (orquesta)           -> estados: Idle/Checking/Ready/Downloading/InstallReady/Error
UpdateInstaller (plataforma)           -> FileProvider + ACTION_VIEW; pre-check via PackageManager
```

### Modelo de dados

- `GitHubRelease`: `tagName`, `versionName`, `versionCode`, `releaseNotes`,
  `apkUrl` (HTTPS), `apkSizeBytes`, `apkSha256?`, `publishedAt`.
- `ApkMetadata`: `applicationId`, `versionCode`, `signatureDigest`.
- `InstalledAppInfo`: `applicationId`, `versionCode`, `signatureDigest`.

### ReleaseMetadataSource (GitHub)

- `GET https://api.github.com/repos/bashln/tcg-colecao/releases?per_page=20`
  com `Accept: application/vnd.github+json` e `User-Agent` explícito.
- Envia `If-None-Match` com o `ETag` da resposta anterior; `304` ⇒ sem mudança
  (reusa a última decisão, sem corpo).
- `403`/`429` com `X-RateLimit-Remaining: 0` ⇒ respeita `X-RateLimit-Reset`
  (backoff). Sem token, o limite é 60 req/h por IP — o cache ETag é essencial.
- Falha de rede/timeout ⇒ estado `Offline` (o app segue funcionando local).

### ReleaseParser (puro)

- Do JSON (`[ {...} ]`), descarta `draft == true` e `pre-release == true`.
- Entre os restantes, escolhe o de maior `versionCode` (estável mais nova).
- Deriva `versionName` do `tag_name` (sem `v`) e `versionCode` por
  `major*10000 + minor*100 + patch`; tag malformada ⇒ descartada com erro tipado.
- Escolhe o asset cujo nome casa `tcg-colecao-v<versão>.apk` (ou
  `content-type` `application/vnd.android.package-archive`); sem asset ⇒ erro.

### UpdateChecker (puro)

- Compara `versionCode` remoto vs `BuildConfig.VERSION_CODE` atual (e
  `versionName` como desempate/validação).
- Remoto **>** atual ⇒ `UpdateAvailable(release)`; caso contrário `NoUpdate`.
- Empate ou remoto menor ⇒ `NoUpdate` (nunca downgrade).

### Integridade e autenticidade

- **Download:** `ApkDownloader` grava no cache privado, confere `apkSizeBytes` e,
  se `apkSha256` estiver presente na API, o SHA-256. Falha ⇒ erro, apaga arquivo.
- **Pre-check (defensiva):** `PackageManager.getPackageArchiveInfo` lê do APK
  baixado `applicationId`, `versionCode` e certificado de assinatura.
  `ApkValidator` (puro) rejeita: pacote diferente, `versionCode` menor/igual,
  assinatura diferente da instalada.
- **Autoritativo:** `PackageInstaller` (diálogo do sistema) re-impo `applicationId`
  + assinatura + `versionCode`. Qualquer violação ⇒ instalação recusada pelo
  sistema, **sem tocar nos dados existentes** (o app instalado permanece).

### Permissão "permitir apps desta fonte"

- Android 8+ exige que o app tenha `REQUEST_INSTALL_PACKAGES` e que a pessoa
  conceda "permitir desta fonte" para **este** app antes de disparar o instalador.
- `canRequestPackageInstalls()` ⇒ se falso, diálogo explicando e atalho para
  `ACTION_MANAGE_UNKNOWN_APP_SOURCES` (pacote deste app). Nunca forçar.
- Esse é o único gating de sistema; não há permissão privilegiada buscada.

## UX (pt-BR)

- **Descoberta:** entrada "Atualizações" em Perfil. Toque ⇒ "Procurando atualização…".
- **Sem atualização:** "Você já está na versão mais recente (X.Y.Z)."
- **Atualização disponível:** "Versão nova disponível: X.Y.Z (atual A.B.C)."
  Notas da release em texto rolável. Botões: "Atualizar" e "Agora não".
- **Progresso:** "Baixando atualização… (n%)" com barra. Botão "Cancelar".
- **Pronto para instalar:** "Download concluído. Toque para instalar." ⇒ dispara
  o instalador do sistema.
- **Instalador do sistema:** o Android mostra o diálogo nativo de instalação/
  atualização. O app **não** o substitui nem o silencia. Se o Play Protect exibir
  aviso, é uma mensagem do sistema: o app não afirma que é "seguro" nem que o
  aviso sumiu; o copy deixa claro que o pacote vem do repositório GitHub público.
- **Erros:** "Não foi possível verificar atualizações. Tente de novo." (offline/
  429/malformado — sem vazar detalhes). Download corrompido: "Download falhou.
  Tente de novo." Pacote incompatível: "Este pacote não pode ser instalado sobre
  a versão atual." (o sistema também recusa).

## Segurança

- Nenhum token/secreto embutido no app ou no repo.
- Download somente de `browser_download_url` HTTPS do próprio GitHub; valida
  tamanho/SHA-256 quando disponíveis; assinatura garantida pelo sistema.
- Nenhum bypass/supressão de Play Protect; nenhum install silencioso; nenhum
  downgrade; dados preservados (instalação recusada nunca apaga dados).
- Chave de assinatura é requisito de continuidade: troca de chave quebraria a
  atualização — mantida imutável; `release.yml` falha fechado.

## Fora de escopo (deliberado)

- Serviço de atualização de terceiros, canal de beta automático, atualização em
  background/silenciosa, rotação de chave, e qualquer integração profunda com
  Obtainium (o Obtainium continua funcionando por cima das releases, sem mudanca).
