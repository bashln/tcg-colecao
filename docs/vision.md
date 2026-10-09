# Visão e destino final

Documento norte do projeto. Ele guarda o ideal completo para que as fatias
menores nunca percam o destino.

Data. 2026-10-09.
Status. Vivo. Atualizar quando uma decisão mudar.

Capturas de referência do Collectr ficam em `docs/reference/collectr/`.

## Resumo

App Android gratuito e local-first, alternativa ao Collectr. O usuário
escaneia uma carta física com a câmera, o app identifica a carta via API,
mostra os dados dela e os preços de TCGplayer (USD) e Cardmarket (EUR), e
guarda tudo numa coleção com valor total.

## Princípios inegociáveis

1. **Grátis.** Sem paywall, sem scan limitado, sem anúncio.
2. **Sem conta.** Nada de login. A coleção vive no device.
3. **Local-first.** O app funciona offline para a coleção já baixada. A rede
   só serve para identificar carta e puxar preço.
4. **Sem lock-in.** Export e backup da coleção em formato aberto.
5. **Documentado e testado.** Toda fatia tem spec e testes. TDD no domínio.
6. **Português primeiro.** UI em pt-BR e dados de carta em pt-BR via TCGdex.

## Destino final, paridade funcional com o Collectr

Derivado das 5 capturas de tela mais o pedido do usuário.

- **Scan.** Câmera com moldura guia, identificação da carta, contador de
  scans sem limite.
- **Home / Overview.** Valor total da carteira, variação em 30 dias, gráfico
  de performance em 1D, 7D, 1M, 3M, 6M, MAX, lista "Most Valuable".
- **Portfolio.** Grade de cartas com imagem, nome, set, raridade, variante,
  condição, quantidade, preço, variação. Busca na coleção. Ações de mercado,
  analisador de troca, ações em lote, exportar.
- **Profile / Stats.** Totais de cartas, selados, graduadas e valor. Estatísticas
  e histórico de transações.
- **Market Movers.** Maiores altas e maiores baixas.
- **Trade Analyzer.** Compara os dois lados de uma troca somando valores.
- **Bulk Actions.** Seleção múltipla e edição em lote.
- **Export.** CSV e JSON, mais backup manual da coleção.

Fora de escopo por decisão de produto.

- Social e feed.
- Loja e venda de selados.
- Contas, sincronização e nuvem.

## Fontes de dados

| Fonte | Papel | Chave | Observação |
| --- | --- | --- | --- |
| TCGdex v2 | Primário | Não exige | Dados em pt-BR. Preços Cardmarket (EUR) e TCGplayer (USD) embutidos no card sob `pricing`. |
| pokemontcg.io v2 | Fallback opcional | Opcional | **Deprecated.** Sem novas chaves. Chaves existentes morrem em 2027-03-01. Fora do caminho crítico. |

Consequências.

- O TCGdex sozinho cobre dados de carta e os dois preços. Ele é a única
  dependência de rede no caminho crítico.
- O pokemontcg.io entra depois, atrás da mesma interface `PriceSource`, como
  cruzamento de preço. Nunca como dependência obrigatória.
- Nenhuma das APIs faz scan. O reconhecimento é nosso.

## Arquitetura alvo

Módulo Gradle `:app` (Android, Compose, CameraX, ML Kit, Room) mais módulo
Kotlin puro `:core` (domínio, parser de scan, regras de valoração, client
HTTP). O `:core` não depende de Android e testa em JVM, o que torna TDD rápido.

Fluxo de camadas.

```
Compose UI -> ViewModel -> Caso de uso -> Repositório (interface)
                                       -> Repositório (impl) -> TCGdex remoto
                                                             -> Room local
```

Regras.

- DTO da API nunca vaza para o domínio. É mapeado na borda.
- Regra de negócio em função pura. Validação e parse só nas bordas.
- Módulos de feature só nascem quando uma fronteira provar que vale.

## Modelo de domínio alvo

- `Card`. id, nome, set, número de colecionador, raridade, hp, tipos, estágio,
  ataques, habilidades, fraquezas, resistências, recuo, ilustrador, marca de
  regulação, legalidades, imagens, variantes disponíveis.
- `CardPrice`. cardId, fonte (TCGplayer, Cardmarket), moeda, variante, campos de
  preço da fonte, data de atualização.
- `CollectionItem`. id, cardId, quantidade, condição, variante, preço pago por
  unidade, moeda, data de entrada, notas.
- `PriceSnapshot`. cardId, fonte, variante, moeda, valor, instante. Base do
  gráfico, porque as APIs não dão série diária.
- `ScanResult`. texto bruto do OCR, número extraído, nome extraído, carta
  casada, confiança.

Enums.

- `Condition`. Near Mint, Lightly Played, Moderately Played, Heavily Played,
  Damaged.
- `CardVariant`. Normal, Holofoil, Reverse Holofoil, First Edition, Promo.
- `PriceSource`. TCGplayer, Cardmarket.

## Roadmap por fatias

Cada fatia tem spec, plano e termina num app que roda.

| Fatia | Entrega | Estado |
| --- | --- | --- |
| S1 | Loop essencial, scan, API, preço, salvar e listar | Spec em andamento |
| S2 | Valoração multi-mercado, USD/EUR, câmbio, variante | Backlog |
| S3 | Snapshots de preço, Home, gráfico, Most Valuable | Backlog |
| S4 | Profile e stats, selados e graduadas | Backlog |
| S5 | Bulk actions e export, backup | Backlog |
| S6 | Market movers e trade analyzer | Backlog |

## Riscos

1. **Precisão do scan.** Nenhuma API reconhece carta. O OCR pode errar nome ou
   número. Mitigação, tela de confirmação com candidatos e fallback de busca
   manual.
2. **Gráfico de 30 dias.** As APIs não dão série diária. Mitigação, snapshots
   no device e uso de `avg1`, `avg7`, `avg30` para semear.
3. **Deprecação do pokemontcg.io.** Mitigação, TCGdex como única dependência
   crítica.
4. **Consistência de variante.** Preço depende da variante (normal, holo,
   reverse). Mitigação, o usuário escolhe a variante ao salvar.

## Registro de decisões

- **D1.** Android nativo com Kotlin e Jetpack Compose. Motivo, melhor
  integração de câmera, ML Kit e vida útil.
- **D2.** Local-first sem conta e sem backend. Motivo, custo zero e
  simplicidade. Coleção em Room.
- **D3.** TCGdex como fonte primária. Motivo, pt-BR e os dois preços embutidos.
- **D4.** pokemontcg.io fica como fallback opcional, desligado por padrão.
  Motivo, deprecação.
- **D5.** Motor de scan inicial é OCR mais busca textual. Motivo, barato,
  on-device e sem infraestrutura. Matching de imagem fica para depois.
- **D6.** Módulo `:core` puro e testável em JVM. Motivo, TDD rápido no domínio.
