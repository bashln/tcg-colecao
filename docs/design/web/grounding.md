# Base existente e requisitos da web

## Estado do repositório

`settings.gradle.kts` inclui apenas `:app` e `:core`. `:app` é Android com Compose, Room, Hilt, CameraX e ML Kit. `:core` é Kotlin/JVM, não Kotlin Multiplatform. Não há `package.json` nem uma aplicação web no repositório.

`CardRepository` oferece busca por nome, busca por número, leitura completa de uma carta e catálogo de expansão. `RoomCardRepository` consulta o Tcgdex e mantém cartas e preços no Room. A busca pode usar dados locais quando a rede falha. A captura de preços acontece ao abrir uma carta completa e ao adicioná-la.

`CollectionRepository` mantém a coleção local. `CollectionItem.id` identifica uma posse exata; o Room limita combinações duplicadas de carta, variante e condição. O editor E1 atualiza uma posse sem recriá-la. Supabase, conta e sincronização ainda não foram implementados. O APK não depende de login.

`OcrParser` e `CandidateRanker` vivem em `:core`, mas o `core` atual usa plugins e dependências JVM. Android captura com CameraX e executa ML Kit. Navegadores iOS não chamam essas APIs nativas. A web precisa de uma captura própria, um mecanismo OCR executável no navegador e uma busca manual de recuperação.

## Fluxo de produto

1. A pessoa abre Coleção e distingue cartas possuídas de cartas faltantes.
2. Busca por nome ou número, filtra por expansão, variante e condição, e edita uma posse exata.
3. A pessoa abre Escanear, concede câmera quando solicitado, fotografa ou seleciona uma imagem.
4. OCR extrai candidatos. A pessoa confirma uma carta do catálogo, escolhe variante, condição, quantidade e preço pago, e só então salva.
5. Falha de permissão, câmera, OCR, API ou rede oferece recuperação explícita. Busca manual não depende de OCR.

## Limites de persistência

A primeira fatia web pode armazenar a coleção no dispositivo sem conta, como o Android. Ela não deve sugerir que uma coleção do navegador já sincroniza com o Android. A sincronização futura exige schema remoto, Auth opcional, RLS, política de conflito e migração de acervo local para conta. Nada disso foi configurado no Supabase remoto.

Fotos devem ficar no navegador enquanto o OCR roda localmente. A requisição ao catálogo deve enviar texto reconhecido, não a imagem capturada. Se o OCR local falhar, a pessoa pode buscar manualmente.

## Referência visual aprovada para prototipação

Os screenshots em `concepts/` definem o primeiro fluxo web em tema escuro independente do Collectr. A navegação mantém Início, Coleção, Escanear e Perfil. Desktop usa navegação lateral e grade de itens. Mobile de 390 px usa lista de itens, controles grandes e barra inferior fixa. Estados desenhados incluem coleção populada, vazia, câmera, confirmação do scan e edição de uma posse exata.

As imagens de cartas são ilustrações genéricas de conceito e não serão copiadas para o produto. A interface real usa imagens de cartas devolvidas pelo catálogo. Os valores e nomes mostrados são fixtures ilustrativas, não preços de mercado.

## Questões para a decisão de arquitetura

- Qual stack permite câmera e OCR em Safari de iPhone sem descarregar a imagem para um serviço?
- O domínio compartilhado vale converter `:core` para Kotlin Multiplatform agora ou o backend futuro é a fronteira estável entre Android e web?
- Como o app web guarda coleções offline hoje sem bloquear migração posterior para sincronização opcional?
- Como o projeto testa UI em Safari e Chromium e roda no GitHub Actions sem segredos?
