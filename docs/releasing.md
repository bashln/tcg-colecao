# Distribuição e releases

O APK é gerado pelo GitHub Actions a cada tag `v*` e publicado como release
do GitHub. O Obtainium aponta para o repositório e instala/atualiza a partir
dessas releases.

## Fluxo

1. Crie um repositório no GitHub e adicione como remoto.
2. Gere um keystore e cadastre os quatro secrets (abaixo).
3. Faça push de uma tag. O workflow `.github/workflows/release.yml` compila o
   APK de release, assina e publica.

```bash
git tag v1.0.0
git push origin v1.0.0
```

A release aparece em `https://github.com/<owner>/<repo>/releases`, com o
arquivo `tcg-colecao-v1.0.0.apk`.

## Alpha

Alphas são GitHub pre-releases, instalados manualmente pelo arquivo APK. O app
não os oferece no canal OTA estável. Use tags `vMAJOR.MINOR.0-alpha.N`; por
exemplo, `v0.3.0-alpha.1` recebe `versionCode=201`, enquanto `v0.3.0` recebe
`300`. Isso permite atualizar `v0.2.0` com o Alpha e depois atualizar o Alpha
com o estável.

Crie e envie tag Alpha somente depois de testar o commit escolhido. Como tags
`v*` acionam o workflow, isso compila e publica automaticamente a pré-release:

```bash
git tag v0.3.0-alpha.1
git push origin v0.3.0-alpha.1
```

O workflow consulta os releases já publicados, exige um código maior que todos
eles, compila com os mesmos secrets de assinatura e verifica o certificado
fixado antes de publicar. Se inventário, assinatura ou validação falhar, nada é
publicado. Tags de release existentes, inclusive rascunhos, reservam seus códigos;
não reutilize uma tag nem tente substituir um APK publicado.

Antes de instalar, faça backup da coleção; Alpha pode conter falhas e usa os
mesmos dados do app estável. Instale o APK manualmente e permita atualizações da
origem escolhida quando o Android pedir. O Play Protect pode mostrar avisos, e a
verificação de desenvolvedor do Android continua aplicável. A assinatura de
release não remove esses avisos nem substitui o registro de desenvolvedor.

## Keystore

Gere uma vez e guarde com cuidado. Se perder, não dá para atualizar um app já
instalado pelo mesmo pacote.

```bash
keytool -genkeypair -v \
  -keystore tcg-release.keystore \
  -alias tcg \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <SENHA> -keypass <SENHA> \
  -dname "CN=TCG Colecao, O=Open Source, C=BR"
```

Codifique em base64 para o secret.

```bash
base64 -w0 tcg-release.keystore > tcg-release.keystore.b64
```

Nunca commite o keystore nem o base64. Eles ficam fora do repositório.

## Secrets do repositório

Cadastre em Settings, Secrets and variables, Actions.

| Secret | Conteúdo |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | o conteúdo de `tcg-release.keystore.b64` |
| `ANDROID_KEYSTORE_PASSWORD` | a senha do keystore |
| `ANDROID_KEY_ALIAS` | `tcg` |
| `ANDROID_KEY_PASSWORD` | a senha da chave |

Sem esses secrets o workflow falha na etapa "Exigir keystore de release" e nada é
publicado. O workflow verifica o SHA-256 do certificado com
`scripts/verify-release-signature.sh`. O fingerprint fixado foi confirmado nos
APKs públicos `v0.1.1` e `v0.2.0`:
`d31a9c98580212663993b2ee7ea757e05fa284ccd2b54ad3b38e6b92a4c3f88f`.
O workflow atual verifica o certificado de todo APK que ele publica, então
qualquer release nova carrega esse certificado ou falha antes de ser publicada.
O nome `CN=TCG Colecao` sozinho não prova que a chave é a mesma. Se o keystore
faltar ou o certificado mudar, o workflow falha em vez de publicar um APK que
não atualiza instalações existentes.

Antes de compilar o APK de release, o workflow roda os testes de domínio e de
unidade do Android. Uma suíte vermelha impede a publicação.

## Versão

A versão vem da tag. O workflow deriva `TCG_VERSION_NAME` (a tag sem o `v`) e
`TCG_VERSION_CODE` e os exporta antes do Gradle. Versões estáveis usam
`major*10000 + minor*100 + patch`; Alpha usa
`major*10000 + minor*100 - 100 + N`. Assim `v0.3.0-alpha.1` recebe 201,
`v0.3.0-alpha.99` recebe 299 e o futuro `v0.3.0` recebe 300.

| Tag | versionName | versionCode |
| --- | --- | --- |
| `v1.2.3` | `1.2.3` | `10203` |
| `v0.3.0-alpha.1` | `0.3.0-alpha.1` | `201` |
| `v0.3.0` | `0.3.0` | `300` |

Os códigos Alpha 201–299 compartilham a faixa com versões estáveis de correção
`v0.2.1`–`v0.2.99`. Publicar essas correções consome números Alpha; depois que
um código de correção maior for publicado, Alphas com código menor serão
rejeitadas. Nunca reduza o código de versão para encaixar uma Alpha.

## Build local

```bash
./gradlew :app:assembleRelease
```

Sem as variáveis `TCG_KEYSTORE_PATH`, `TCG_KEYSTORE_PASSWORD`, `TCG_KEY_ALIAS`
e `TCG_KEY_PASSWORD` no ambiente, o release usa a assinatura de debug. Sem
`TCG_VERSION_NAME` e `TCG_VERSION_CODE`, usa `1.0` e `1`. Isso vale só para o
build local; a tag no CI sempre usa a chave de release e a versão da tag.

## Obtainium

No Obtainium, adicione o app com a URL do repositório, por exemplo
`https://github.com/<owner>/<repo>`. Ele lê as releases e instala o APK.

## Atualização no app (OTA)

O app também oferece atualização opcional a partir das releases estáveis do
GitHub (Perfil → Atualizações → Procurar atualização). É manual: não há verificação
automática a cada início para não gastar a cota da API do GitHub.

- O app só considera tags estáveis exatos `vMAJOR.MINOR.PATCH` (rascunhos e
  pré-releases são ignorados) e só oferece se o `versionCode` for maior que o
  atual. O `versionCode` é `major*10000 + minor*100 + patch`, com `minor`/`patch`
  em 0..99.
- O APK é baixado por HTTPS, conferido tamanho e SHA-256, e entregue ao
  instalador do sistema. A instalação é sempre confirmada por você.
- O Obtainium continua funcionando; o OTA do app é um caminho adicional, não substitui.

O guard `scripts/validate-release.sh` (testado por
`scripts/validate-release.test.sh`) roda no workflow de release e recusa tag
fora do formato, `versionCode` duplicado/menor que o último publicado e nome de
APK divergente. O workflow falha fechado e também verifica a assinatura com
`apksigner` e registra o SHA-256 do APK.

## Conceitos de segurança (não confundir)

- **Assinatura do APK:** o `release.yml` fixa o SHA-256 do certificado usado
  pelos APKs `v0.1.1` e `v0.2.0`. A rotação da chave não é suportada pelo OTA
  atual. O Android exige assinatura compatível para atualizar o mesmo
  `applicationId`.
- **Permissão "permitir apps desta fonte":** ajuste do Android para **este app**
  instalar um APK. O OTA pede isso só na primeira atualização. É diferente de
  assinatura e de verificação de desenvolvedor.
- **Play Protect:** verificação do Google para apps fora da Play Store. Pode
  mostrar aviso ao instalar nosso APK. **Este app não remove esse aviso** e não
  afirma que o pacote é "certificado" pelo Google.
- **Verificação de desenvolvedor Android:** registro de pacote/assinatura junto
  ao Google para distribuição fora da Play Store (ver abaixo). É externa ao app.

## Verificação de desenvolvedor Android (Brasil)

Desde 2026-09-30 o Android aplica proteções de verificação de desenvolvedor no
Brasil para instalar apps fora da Play Store. Para distribuição ampla, o pacote
`app.tcg.app` e a **impressão digital do certificado de assinatura de release**
precisam estar registrados/verificados. Isso é feito no console do Google, é
externo a este repositório e **não é automatizado por este projeto**.

- Conta completa: US$ 25 (valor oficial atual; pode mudar). Conta "Limited" é
  grátis para até 20 dispositivos autorizados.
- Nenhum pagamento, documento de identidade ou conta foi criado por este projeto.
- Registro/verificação **não** remove o aviso do Play Protect.

Para obter a impressão digital do certificado de release:

```bash
apksigner verify --print-certs out/tcg-colecao-vX.Y.Z.apk
```

Guarde o SHA-256 do certificado (campo `Signer #1 certificate SHA-256 digest`)
— é o que o registro de pacote pede.
