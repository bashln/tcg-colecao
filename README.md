# TCG Coleção

App gratuito e de código aberto para cuidar da sua coleção de cartas Pokémon. Fotografe uma carta, confirme qual é, e acompanhe quantidade, condição, variante e preço. A coleção fica no seu aparelho e funciona sem conta e sem internet.

Alternativa ao Collectr, sem anúncio, sem limite de leitura e sem assinatura. Para iOS, a versão web cobre a coleção no navegador.

## O que dá para fazer

- **Escanear** uma carta com a câmera ou escolher uma foto. A leitura roda no próprio aparelho e você confirma o resultado antes de salvar.
- **Coleção** com busca, filtros por expansão, variante e condição, e valor estimado do acervo.
- **Faltam** mostra as cartas que ainda não estão na coleção, quando o catálogo da expansão está completo.
- **Listas** separam subcoleções, por exemplo as cartas que você quer trocar.
- **Perfil** exporta um backup JSON e restaura em outro aparelho.
- **Conta opcional** sincroniza a mesma coleção entre o Android e o navegador. Sem conta, nada é enviado.

## Instalar no Android

O app é distribuído pelo GitHub, não pela Play Store.

1. Abra [a última versão](https://github.com/bashln/tcg-colecao/releases/latest).
2. Baixe o arquivo `tcg-colecao-vX.Y.Z.apk`.
3. Toque no arquivo e permita a instalação de apps dessa origem quando o Android pedir.
4. Abra o app e escolha **Escanear**.

O Android exige Android 8.0 ou mais recente. O Play Protect pode mostrar um aviso porque o app não vem da Play Store. Para receber atualizações automáticas, instale pelo [Obtainium](https://github.com/ImranR98/Obtainium) e aponte para `https://github.com/bashln/tcg-colecao`.

Atualizar por cima mantém a coleção. Desinstalar apaga os dados locais, então baixe um backup em **Perfil** antes.

## Versão web

O site é gerado a partir de `web/` e guarda a coleção no IndexedDB do navegador. Ele não substitui o backup: limpar os dados do site apaga a coleção local. Não existe uma instância pública publicada por este repositório ainda. Para rodar:

```sh
cd web
npm ci
npm run dev
```

## Privacidade

A coleção fica no aparelho. Nenhum dado é enviado sem ação sua, e não há rastreamento, anúncio ou relatório de erro automático. Leia [a política de privacidade](docs/privacy.md) para ver o que cada serviço externo recebe.

## Desenvolvimento

Requisitos:

- JDK 17.
- Android SDK com `platforms;android-35` e `build-tools;35.0.0`.
- Node 26 ou mais recente, só para a versão web.

Comandos do Android, a partir da raiz:

```sh
./gradlew :core:test :app:testDebugUnitTest   # domínio e ViewModels
./gradlew :app:compileDebugAndroidTestKotlin  # compila os testes de aparelho
./gradlew :app:assembleDebug                  # gera app/build/outputs/apk/debug/app-debug.apk
```

O app usa Compose, Hilt, Room, CameraX e ML Kit. O domínio compartilhado fica em `:core`, sem dependência de Android, e é testado no JVM.

Para verificar um fluxo no aparelho ou no navegador, siga [docs/verification/README.md](docs/verification/README.md). Para releases e assinatura, leia [docs/releasing.md](docs/releasing.md).

## Contribuir

Abra uma issue para relatar um problema ou sugerir algo. Se o relato envolver a sua coleção, descreva o caso sem anexar o backup. A coleção é pessoal e o backup carrega todos os seus itens.

## Licença

GNU General Public License v3.0. Veja [LICENSE](LICENSE).
