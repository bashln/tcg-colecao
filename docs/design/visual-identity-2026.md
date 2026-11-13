# Identidade visual 2026

Direção escolhida: catálogo de colecionador. Papel, tinta, serifa editorial e números alinhados. O objetivo é que o app pareça um catálogo impresso bem feito, não um formulário do Material.

## O que muda na sensação

- Fundo de papel quente, superfícies um tom acima do fundo. O cartão se separa por valor, não por sombra.
- Uma cor de ação, verde profundo. Latão para destaque secundário. Vinho só para erro e perigo.
- Títulos e valores grandes em serifa de jornal. Texto de leitura em sans neutra. Números pequenos em monoespaçado, para preço e quantidade alinharem em coluna.
- Cantos de 2 a 12 dp. Sem elevação, sem gradiente decorativo, sem pill em tudo.
- Régua de 1 dp no lugar de borda cinza claro: contraste real.

## Tokens

### Cor, tema claro

| Papel | Valor |
| --- | --- |
| Fundo | `#F7F2E7` |
| Superfície | `#FFFCF5` |
| Superfície variante | `#ECE4D4` |
| Tinta | `#17140F` |
| Tinta secundária | `#5C5446` |
| Ação | `#1F4A3D` |
| Ação sobre | `#FFFFFF` |
| Latão | `#8A5A2B` |
| Vinho | `#7A2E2E` |
| Régua | `#D8CFBD` |

### Cor, tema escuro

| Papel | Valor |
| --- | --- |
| Fundo | `#12100D` |
| Superfície | `#191612` |
| Superfície variante | `#2A241C` |
| Tinta | `#EFE7D8` |
| Tinta secundária | `#C9BFAE` |
| Ação | `#9FD0BA` |
| Latão | `#E0B884` |
| Vinho | `#E5A3A3` |
| Régua | `#3A342B` |

Os pares de texto passam WCAG AA em `ColorContrastTest`.

### Tipografia

| Papel | Família | Tamanho |
| --- | --- | --- |
| Título de tela | Instrument Serif | 24 sp |
| Título de destaque | Instrument Serif | 28 a 34 sp |
| Valor de dinheiro grande | Instrument Serif | 34 sp |
| Título de seção e de item | Inter Medium | 16 a 20 sp |
| Texto | Inter | 12 a 16 sp |
| Rótulo, metadado | Inter Medium, com tracking | 10 a 11 sp |
| Número pequeno, preço, quantidade | IBM Plex Mono | 12 a 14 sp |

### Forma e espaço

Cantos: 2, 4, 6, 8 e 12 dp. Escala de espaço: 4, 8, 12, 16, 24, 32, 48. Margem lateral de tela: 20 dp. Distância entre seções: 32 dp. Distância entre itens da mesma seção: 12 dp.

## Regras de composição

1. Toda tela tem um título em serifa alinhado à margem de 20 dp.
2. Cartão de conteúdo: superfície, 1 dp de régua, canto de 8 dp, sem sombra, sem gradiente.
3. Ação principal é botão sólido na cor de ação. Ação secundária é texto com régua. Nunca dois botões sólidos lado a lado.
4. Preço em mono, com moeda e duas casas. Ausência de preço aparece como texto secundário, nunca com o mesmo peso de um valor real.
5. Metadado vai em rótulo de 10 a 11 sp com tracking, não em texto de corpo.
6. Nada de emoji como ícone. Ícone do conjunto Material Outlined, 20 dp, alinhado ao texto.
7. Movimento é curto: 120 a 180 ms, fade e deslocamento de 8 dp. Sem quique, sem escala elástica.
8. Contraste de texto nunca abaixo de 4,5:1. Alvo de toque nunca abaixo de 48 dp.
9. A tela inteira rola, incluindo título. Nada de cabeçalho fixo com metade da tela presa, exceto onde a rolagem faz parte do trabalho (coleção com busca).
10. O mesmo dado tem uma formatação só. Preço, percentual e data vivem em um único helper.

## Ordem de aplicação

1. Tokens, fontes e tema. Já feito nesta rodada.
2. Home como prova de direção, com captura no aparelho.
3. Coleção, detalhe da carta, perfil e listas.
4. Varredura final: emojis, bordas duplicadas, espaçamentos fora da escala, alvos menores que 48 dp.

## O que fica de fora

Ícone do app e arte de loja, que dependem de decisão à parte. Animações de transição entre telas. Redesenho do scanner, que precisa de teste com carta real antes de mexer no enquadramento.
