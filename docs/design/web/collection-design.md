# Design web: coleção e fluxo de scan

## Ideia visual

Interface de coleção escura, densa e legível. Fundo carvão, texto quase branco, metadados cinza, linhas de separação discretas e acento turquesa. A navegação fixa quatro destinos. Não reproduz a marca ou os textos comerciais do Collectr.

## Tokens iniciais

- Fundo: `#0B0E10`.
- Superfície: `#14191D`.
- Borda: `#303941`.
- Texto principal: `#F3F5F7`.
- Texto secundário: `#AAB4BF`.
- Ação e seleção: `#12D4D2`.
- Tipografia: `Inter, system-ui, sans-serif`, com tamanhos explícitos para conteúdo e controles.
- Controles de toque: altura mínima de 44 px; ações primárias de 52 px ou mais.

Os valores são aproximações extraídas dos conceitos. A implementação deve provar contraste AA para texto e foco visível para teclado.

## Coleção no desktop

Em 1440 px, a navegação lateral fixa Início, Coleção, Escanear e Perfil. A área principal mostra o título Coleção, busca, alternância Tenho/Faltam, filtros de Expansão/Variante/Condição e ação Escanear carta. Um resumo de coleção precede uma grade de três colunas. Cada item identifica uma posse exata e expõe carta, expansão, número, variante, condição, quantidade, preço atual e edição.

## Coleção no iPhone

Em 390 px, a barra lateral some. O cabeçalho mostra a marca e uma entrada rápida para scan. Busca e alternância usam toda a largura. Filtros podem rolar horizontalmente. Itens ficam em lista vertical com imagem e dados organizados em duas colunas. Uma barra inferior mantém os quatro destinos e o destino atual fica indicado por forma, texto e cor.

O estado vazio omite totais e cartas inventadas. Ele explica o que fazer e oferece Escanear carta como ação principal e Buscar no catálogo como alternativa.

## Scan e edição

O modo câmera mantém o visor desobstruído, mostra como enquadrar e evitar reflexos, e oferece Fotografar e Escolher foto. A confirmação mostra a foto capturada e candidatos do catálogo antes de gravar. A pessoa revisa variante, condição, quantidade e preço pago.

Editar posse mostra a carta e deixa claro que as mudanças afetam aquela posse. Quantidade, variante, condição e preço pago são editáveis. Preço de mercado aparece separado, com fonte e data de captura. Remover da coleção fica separado de Salvar alterações e pede confirmação.

## Estados obrigatórios

Busca e catálogo cobrem carregando, resultados, sem resultados, resultado parcial, falha e retry. Câmera cobre permissão pendente, negada, indisponível, ativa e imagem escolhida. OCR cobre carregando, correspondência, baixa confiança, falha e busca manual. Gravação cobre salvando, sucesso e conflito. Todas as ações recebem feedback. O fluxo permanece navegável por teclado, leitores de tela e toque.

## Inventário de conceitos

- `concepts/collection-desktop.png`: grade e navegação lateral.
- `concepts/collection-mobile.png`: lista móvel e navegação inferior.
- `concepts/collection-empty.png`: primeira execução sem posse.
- `concepts/scanner-camera.png`: captura móvel.
- `concepts/scan-confirmation.png`: confirmação editável antes de salvar.
- `concepts/collection-item-edit.png`: edição e remoção confirmada.

## Limites desta fatia

Os conceitos não incluem Início, Perfil, gráfico, histórico de preço ou análise de troca. A etapa de protótipo deve escolher stack e persistência primeiro. Não reutilizar as imagens ilustrativas de cartas no produto. A interface deve exibir imagens reais do catálogo e o texto de mercado existente com fonte/moeda.
