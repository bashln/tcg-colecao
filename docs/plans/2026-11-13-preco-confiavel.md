# Plano. Preço de carta confiável

O goal desta rodada é fechar dois defeitos na leitura e na gravação de preço de
mercado. Carta holo passa a ser valorada pelo preço holo do Cardmarket quando
existe. Um refresh de preço deixa de apagar o último preço válido conhecido.

Quem usa o app nota duas coisas. Cartas holo batem com o valor de mercado real
da impressão, em vez do preço da versão normal. O valor total da coleção para
de cair para zero, ou para "sem preço", depois de uma busca ou de um refresh
que veio sem bloco de pricing.

Quem mantém o código herda uma regra única e testada sobre o que conta como
preço válido, aplicada na borda da API e na escrita local.

## Como ler este plano

Uma caixa é uma unidade verificável. A evidência de cada caixa é um teste que
passa, um comando com saída, ou um run de CI no SHA do head. Marque a caixa só
quando a evidência existir.

O comportamento de origem vem do PokéCollector, repo AGPL-3.0. Nada de código
copiado. A regra de validação e a ordem de fallback foram reimplementadas a
partir da descrição em `backend/services/price_utils.py` e do payload real da
API do TCGdex.

## Goal e critério de conclusão

O critério é falsificável e roda nesta máquina, mais CI para a web.

- [ ] `./gradlew :core:test` verde com os casos novos de preço holo e de valor inválido.
- [ ] `./gradlew :app:testDebugUnitTest` verde com o caso de refresh sem pricing e o de preço inválido.
- [ ] `./gradlew :app:assembleDebug` verde.
- [ ] Workflow `web` do GitHub Actions verde no SHA do head, com `test:unit` e `build`.
- [ ] O diff toca só os arquivos nomeados em cada unidade.

Fica fora desta rodada, com motivo.

- Tie-breaker visual do scanner. Precisa de fotos reais de falha em aparelho, que não existe neste ambiente headless, e de calibragem de limiar. Fica como a próxima rodada, com o desenho já levantado do repo de referência.
- Base de custo e P&L do portfólio. É decisão de produto sobre qual base usar e mexe em snapshot versionado.
- Importação CSV e features de binder, selados e wishlist.

## Unidade 1. Preço holo do Cardmarket

**Arquivos.**

- [ ] `core/src/main/kotlin/app/tcg/core/data/tcgdex/Dto.kt`
- [ ] `core/src/main/kotlin/app/tcg/core/data/tcgdex/Mappers.kt`
- [ ] `core/src/test/resources/fixtures/card_holo_cardmarket.json` (novo)
- [ ] `core/src/test/kotlin/app/tcg/core/data/tcgdex/TcgdexCardSourceTest.kt`
- [ ] `web/src/catalog/tcgdex.ts`
- [ ] `web/src/catalog/tcgdex.test.ts` (novo)
- [ ] `web/package.json` (incluir o teste novo em `test:unit`)

**Contrato.**

O DTO do Cardmarket ganha os campos holo que a API já devolve, `avg-holo`,
`low-holo`, `trend-holo`, `avg1-holo`, `avg7-holo`, `avg30-holo`. A resolução
de um preço é uma cadeia ordenada de campos por variante, escolhida pelo
primeiro valor positivo e finito.

- Holo, cadeia holo primeiro e campo base como fallback.
- Normal, reverse holo e demais variantes, campo base apenas. O Cardmarket não publica um campo de reverse holo, e o campo holo é o preço da impressão holo comum, que inflaria o reverse.
- `market` continua sendo a média (`avg`), agora com `avg-holo` na frente quando a variante é holo.
- `low`, `trend`, `avg1`, `avg7`, `avg30` seguem a mesma cadeia.
- Valor nulo, zero, negativo, NaN ou infinito vira `null` na borda. Nunca entra no domínio.

**Você vê.**

Payload real de `swsh3-3` tem as duas variantes listadas como normal e reverse, e o bloco Cardmarket traz `avg: 0.06` com `avg-holo: 0.26`. Um card holo de verdade, `base1-4`, traz `avg: 453.87`, `avg-holo: null` e `trend-holo: 123.63`. No teste, a variante holo recebe `market` do fallback base quando `avg-holo` é nulo e `trend = 123.63` do campo holo, o shadowless com `trend-holo: 0` cai para o `trend` base, e a variante normal continua lendo `avg`.

**Verificação.** `./gradlew :core:test` e, na web, `npm run test:unit` no CI.

## Unidade 2. Refresh não apaga preço válido

**Arquivos.**

- [ ] `core/src/main/kotlin/app/tcg/core/domain/price/PriceMerge.kt` (novo)
- [ ] `core/src/test/kotlin/app/tcg/core/domain/price/PriceMergeTest.kt` (novo)
- [ ] `app/src/main/kotlin/app/tcg/app/data/local/Daos.kt`
- [ ] `app/src/test/kotlin/app/tcg/app/data/local/DaoTest.kt`

**Contrato.**

Uma função pura no `:core` decide a mescla, e o DAO aplica dentro da mesma
transação que hoje apaga e reinsere.

```kotlin
object PriceMerge {
    fun merge(existing: List<CardPrice>, incoming: List<CardPrice>): List<CardPrice>
}
```

A chave é o par fonte e variante. As regras.

- Preço de entrada com `market` positivo substitui o existente da mesma chave.
- Preço de entrada sem `market` positivo mantém o existente da mesma chave, com o `updatedAt` antigo, sem inventar observação nova.
- Entrada de chave nova entra, mesmo sem `market`, para a UI saber que a variante existe.
- Fonte fora do feed de mercado, hoje LigaPokemon, não passa pela mescla e continua intocada.

**Você vê.**

`DaoTest` grava um preço TCGPLAYER de 10.0, chama `captureCardPrices` com o
fixture `card_without_pricing.json` e lê 10.0 de volta. O caso espelho, com
preço novo de 12.5, lê 12.5. O caso de payload com `marketPrice: 0` mantém o
valor anterior. Uma coleta com preço válido continua gerando observação
diária nova.

**Verificação.** `./gradlew :app:testDebugUnitTest --tests '*DaoTest*'` e depois a suíte inteira.

## Checkpoint de throughput

- **Blocking first steps.** Ler o payload real do TCGdex e o caminho de escrita atual antes de qualquer código. Feito nesta sessão, com `base1-4` e `Daos.kt`.
- **Independent workstreams.** Kotlin e web são workstreams independentes para a unidade 1, com arquivos disjuntos. A unidade 2 é só Kotlin e serializa depois da unidade 1 no mesmo checkout, porque as duas mexem no contrato de preço.
- **Shared mutable state.** Um único dono escreve no checkout, em worktree isolado. Não há escrita concorrente no mesmo arquivo.
- **Smallest safe decomposition.** Um dono para as duas unidades. A superfície pública é `PriceMerge` mais o DTO, e separar em dois donos custaria mais em integração do que em paralelismo, com 2 arquivos de produção na unidade 1 e 2 na unidade 2.

## Riscos

- **Confirmado.** O valor exibido de cartas holo muda, para cima ou para baixo. É o objetivo. O exemplo `swsh3-3` sai de 0.06 para 0.26 no preço médio da impressão holo, e entra no corpo da PR para o operador conferir.
- **Confirmado.** Reverse holo fica de fora do campo holo por decisão registrada no contrato. O Cardmarket não publica preço de reverse, e usar o campo holo inflaria a variante.
- **Hipótese não verificada.** Alguns sets podem trazer `avg-holo` igual ao `avg`. Nesse caso nada muda. O teste de fixture real cobre o caso conhecido, não o catálogo inteiro.
- **Confirmado por desenho.** Preço preservado não gera observação diária. O gráfico continua honesto, com o último dia realmente observado.
- **Dependência externa.** A suíte web não roda nesta máquina porque o Node local é 18 e o projeto exige 26. A evidência da web é o workflow `web` no CI.

## Evidência de origem

Clonado em `/tmp/pokecollector-inspect`, commit da main no dia 2026-11-13.

- `backend/services/price_utils.py` define `PRICE_FIELDS` com os campos holo e `preserve_existing_prices_for_invalid_update`.
- `backend/api/recognize.py` define o tie-breaker por pHash, para a próxima rodada.
- `backend/services/portfolio_valuation.py` define a base de custo e o P&L, para a rodada de produto.
