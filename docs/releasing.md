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
publicado. O release só publica depois de confirmar, com `apksigner verify
--print-certs`, que o APK está assinado com a chave de release (`CN=TCG Colecao`).
Se o keystore faltar ou a assinatura não casar, a tag falha em vez de distribuir
um APK assinado com debug.

## Versão

A versão vem da tag. O workflow deriva `TCG_VERSION_NAME` (a tag sem o `v`) e
`TCG_VERSION_CODE` (`major*10000 + minor*100 + patch`) e os exporta antes do
Gradle. Assim cada release tem `versionCode` e `versionName` próprios, como o
Obtainium e as atualizações do Android exigem.

| Tag | versionName | versionCode |
| --- | --- | --- |
| `v1.2.3` | `1.2.3` | `10203` |

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
