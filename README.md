# TCG Coleção

[![CI](https://github.com/bashln/tcg-colecao/actions/workflows/ci.yml/badge.svg)](https://github.com/bashln/tcg-colecao/actions/workflows/ci.yml)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)
![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)
![TypeScript](https://img.shields.io/badge/TypeScript-5.9-3178C6?logo=typescript&logoColor=white)

Aplicativo Android e versão web para organizar uma coleção de cartas Pokémon: escanear uma carta com a câmera, confirmar o resultado, registrar quantidade, condição, variante e preço pago, e acompanhar o valor do acervo. A coleção fica no aparelho por padrão e funciona sem conta e sem internet. A conta é opcional e sincroniza a mesma coleção entre Android e navegador.

## Visão Geral e Problema Resolvido

Coletores de cartas costumam controlar o acervo em planilhas ou em aplicativos com anúncios, limites de leitura e assinatura. TCG Coleção resolve isso com um fluxo local-first. A leitura da carta roda no próprio aparelho, a coleção não é enviada a nenhum servidor sem conta, e não há rastreamento, anúncio ou relatório automático de erro. O projeto cobre o ciclo completo de manutenção da coleção: escaneamento, busca e filtros, lista de faltantes, listas secundárias, backup JSON e sincronização opcional entre Android e web.

## Arquitetura e Decisões Técnicas

- `:core` é um módulo Kotlin JVM puro, sem dependência do Android. Ele contém o domínio compartilhado e é testado na JVM. `:app` depende de `:core` e concentra a interface Compose, a injeção com Hilt, o armazenamento Room, a câmera CameraX e o OCR com ML Kit.
- A interface Android usa Jetpack Compose com Material 3, Navigation Compose e Hilt. O estado de tela fica em ViewModels; a navegação e os estados de carregamento/erro são tratados explicitamente.
- O armazenamento local usa Room para a coleção, preços e snapshots. Migrações e consultas ficam no módulo de dados, separadas da UI.
- O OCR roda localmente com ML Kit no Android e com Tesseract.js na web. A foto não é enviada a nenhum servidor; apenas o texto reconhecido segue para a busca no catálogo TCGdex.
- A versão web fica em `web/` e usa React 19, TypeScript e Vite. A coleção é persistida no IndexedDB, com busca manual no TCGdex, edição exata, remoção confirmada, filtros e lista de faltantes.
- A sincronização é opcional e baseada em Supabase. O cliente usa apenas a chave publishable, protegida por Row Level Security. As migrações e o harness de PostgreSQL ficam em `supabase/`, com RPCs para push, conflito e vínculo entre dispositivos.
- O backup é um arquivo JSON versionado, com importação validada, mesclagem por quantidade, recusa de conflito e limite de 50 MB. A importação não substitui a coleção sem confirmação.
- A segurança de release é fail-closed. O APK de release exige keystore e secrets do Supabase, valida a assinatura com um certificado fixado e não publica quando os testes de unidade falham.
- A privacidade é tratada como requisito de produto. Sem conta, a coleção não sai do aparelho. Não há rastreamento, anúncio ou relatório automático de erro. A política completa está em `docs/privacy.md`.

## Pré-requisitos e Instalação Rápida

Requisitos:

- JDK 17.
- Android SDK com `platforms;android-35` e `build-tools;35.0.0`.
- Node 26 ou mais recente, apenas para a versão web.
- Supabase CLI e PostgreSQL, apenas para alterar ou validar o schema.

Clone e configure:

```sh
git clone https://github.com/bashln/tcg-colecao.git
cd tcg-colecao
cp .env.example .env
set -a; . ./.env; set +a
```

O Gradle e os scripts leem variáveis de ambiente, não o arquivo `.env`. Use o bloco acima no shell atual. Preencha `.env` apenas se for usar sincronização ou assinatura de release. Para rodar sem conta, deixe as variáveis de Supabase vazias.

Testes e build Android:

```sh
./gradlew :core:test :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

O APK de debug fica em `app/build/outputs/apk/debug/app-debug.apk`.

Versão web:

```sh
cd web
cp .env.example .env.local
npm ci
npm run test:unit
npm run build
npm run dev
```

O arquivo `.env.local` é opcional. Deixe as variáveis vazias para usar a coleção sem conta.

Harness Supabase:

```sh
cd supabase/tests
npm ci
npm test
```

CI local recomendado:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
cd web && npm ci && npm run test:unit && npm run build
cd ../supabase/tests && npm ci && npm test
```

## Uso / Exemplos / Demonstração

Exemplo de fluxo de uso:

1. Abra o app e escolha **Escanear**.
2. Fotografe ou selecione uma imagem da carta.
3. Confirme o resultado da leitura antes de salvar.
4. Ajuste quantidade, condição, variante e preço pago.
5. Use **Faltam** para ver as cartas ausentes de expansões com catálogo completo.
6. Exporte o backup JSON em **Perfil**.

<!-- TODO: Adicionar screenshot da tela de escaneamento -->
<!-- TODO: Adicionar screenshot da coleção com filtros -->
<!-- TODO: Adicionar screenshot da lista de faltantes -->
<!-- TODO: Adicionar screenshot da versão web -->

Exemplo de build com sincronização habilitada:

```sh
export TCG_SUPABASE_URL=https://your-project.supabase.co
export TCG_SUPABASE_PUBLISHABLE_KEY=sb_publishable_replace-me
./gradlew :app:assembleDebug
```

Exemplo de execução do verificador de sincronização hospedada:

```sh
export TCG_SUPABASE_URL=https://your-project.supabase.co
export TCG_SUPABASE_PUBLISHABLE_KEY=sb_publishable_replace-me
export HOSTED_EMAIL_A=account-a@example.test
export HOSTED_PASSWORD_A=replace-me
export HOSTED_EMAIL_B=account-b@example.test
export HOSTED_PASSWORD_B=replace-me
node scripts/verify-hosted-sync.mjs
```

## Privacidade

A coleção fica no aparelho. Sem conta, ela não é enviada a nenhum servidor. Não há rastreamento, anúncio ou relatório automático de erro. A política completa está em [docs/privacy.md](docs/privacy.md).

## Contribuir

Abra uma issue para relatar um problema ou sugerir algo. Se o relato envolver a sua coleção, descreva o caso sem anexar o backup. A coleção é pessoal e o backup carrega todos os seus itens.

## Licença

GNU General Public License v3.0. Veja [LICENSE](LICENSE).
