# Spec 001. Loop essencial, scan, API e preço

Primeira fatia de implementação. Foco em scan, API e preço. Sem gráficos.

Data. 2026-10-09.
Fatia. S1.
Referência. `docs/vision.md`.

## Objetivo

Fechar o loop essencial. O usuário aponta a câmera para uma carta, o app
reconhece a carta, mostra os dados e os dois preços, e permite salvar o item
numa coleção local que soma o valor total.

## Escopo

Incluído.

- Projeto Android em Kotlin e Compose, módulos `:app` e `:core`.
- Client TCGdex para busca e leitura de carta, em pt-BR.
- Captura com CameraX e OCR com ML Kit Text Recognition.
- Parser de OCR que extrai nome, número de colecionador e HP.
- Busca de candidatos, ranqueamento e tela de confirmação.
- Tela de detalhe da carta com dados e os dois preços, TCGplayer em USD e
  Cardmarket em EUR.
- Salvar item com quantidade, condição e variante.
- Lista simples da coleção, busca e valor total.
- Persistência em Room.

Excluído, fica para fatias seguintes.

- Gráfico e histórico de preço. S3.
- Conversão de moeda e alternância USD/EUR global. S2.
- Perfil, selados, graduadas, estatísticas. S4.
- Bulk actions, export, backup. S5.
- Market movers, trade analyzer. S6.
- pokemontcg.io. Fora do caminho crítico.

## Fluxo do usuário

1. Abre o app e toca em escanear.
2. Aponta a câmera para a carta dentro da moldura.
3. O app lê o texto e consulta o TCGdex.
4. Aparecem candidatos ranqueados. O usuário confirma a carta certa, ou busca
   manualmente se nenhum bater.
5. Abre a tela da carta. Vê dados, imagem e os dois preços.
6. Define quantidade, condição e variante e salva.
7. A carta entra na coleção e o valor total é recalculado.

## Arquitetura

Módulos.

- `:core`. Kotlin puro, sem Android. Domínio, parser de OCR, ranqueamento,
  client TCGdex, regras de valoração. Testável em JVM.
- `:app`. Android. Compose, CameraX, ML Kit, Room, injeção de dependência.

Pacotes em `:core`.

```
core/
  domain/model        Card, CardPrice, CollectionItem, ScanResult, enums
  domain/valuation    Valuation (função pura)
  scan                OcrParser, CandidateRanker
  data/tcgdex         Dto, TcgdexClient, mappers
  data/repository     CardRepository (interface), CollectionRepository (interface)
```

Pacotes em `:app`.

```
app/
  data/local          Room entities, DAOs, database
  data/repository     implementações que ligam rede e Room
  ui/scan             ScanScreen, ScanViewModel, CameraCapture
  ui/card             CardDetailScreen, CardDetailViewModel
  ui/collection       CollectionScreen, CollectionViewModel
  di                  módulos Hilt
```

Camadas. UI Compose chama ViewModel. ViewModel chama caso de uso ou repositório.
Repositório é interface no `:core` e implementação no `:app`. DTO nunca vaza.

## Contratos principais

```kotlin
// :core

interface CardRepository {
    suspend fun search(query: CardQuery): List<Card>
    suspend fun getCard(id: String): Card?
}

data class CardQuery(
    val name: String? = null,
    val collectorNumber: String? = null,
    val setId: String? = null,
    val language: String = "pt",
)

interface CollectionRepository {
    suspend fun add(item: CollectionItem)
    suspend fun list(): List<CollectionEntry>
    suspend fun update(item: CollectionItem)
    suspend fun remove(itemId: String)
}

// item guardado, com a carta resolvida
data class CollectionEntry(val item: CollectionItem, val card: Card)

// parser puro, entra o texto do OCR, sai leitura estruturada
object OcrParser {
    fun parse(lines: List<OcrLine>): ParsedCardText
}

data class OcrLine(val text: String, val top: Float, val height: Float)

data class ParsedCardText(
    val name: String?,
    val collectorNumber: String?, // "051/131" vira "051"
    val promoCode: String?,        // "SWSH027"
    val hp: Int?,
    val rawText: String,
)

// ranqueamento puro
object CandidateRanker {
    fun rank(parsed: ParsedCardText, candidates: List<Card>): List<Scored<Card>>
}

// valoração pura
object Valuation {
    fun itemValue(card: Card, variant: CardVariant, source: PriceSource): Money?
    fun portfolioTotal(entries: List<CollectionEntry>, source: PriceSource): Money
}
```

## Pipeline de scan

1. **Captura.** CameraX com PreviewView e moldura guia. O MVP usa moldura fixa e
   captura o frame inteiro recortado pela moldura. Detecção de borda automática
   fica para depois, para não travar o loop essencial.
2. **OCR.** ML Kit Text Recognition v2 sobre o recorte. Saída é uma lista de
   linhas com texto e posição vertical.
3. **Parse, função pura.**
   - Número de colecionador. Procura o padrão `\d{1,3}\s*/\s*\d{1,3}`. Guarda
     a parte da esquerda, sem zeros à esquerda.
   - Código promo. Procura `[A-Z]{2,4}\d{2,3}`, tipo `SWSH027`.
   - Nome. Pega o bloco de texto mais alto e de maior altura de fonte, descarta
     ruído como `BASIC`, `STAGE`, `HP`, energias.
   - HP. Procura `HP\s*\d+`.
4. **Busca.** Com número e nome monta `CardQuery`. Preferência de caminho.
   - Se tem `setId` e `collectorNumber`, busca direta por set e número.
   - Senão, busca por `collectorNumber`.
   - Senão, busca por `name`.
5. **Ranqueamento, função pura.** Pontua candidato por igualdade de número,
   igualdade de HP e similaridade de nome. Ordena.
6. **Confirmação.** Mostra até 10 candidatos com imagem e preço. Um já vem
   selecionado se a confiança for alta. Se nada bater, abre a busca manual.
7. **Salvar.** Escolhe quantidade, condição e variante. Grava `CollectionItem`.

## Integração TCGdex

Base. `https://api.tcgdex.net/v2/{lang}/`. Idioma `pt` para português do Brasil.
Verificado em 2026-10-09 contra a API real.

Endpoints usados, todos verificados.

- `GET /cards/{id}` devolve o card completo, com `pricing`.
- `GET /cards?name={name}` busca por nome, com paginação.
- `GET /cards?localId={n}` busca por número de colecionador. Confirmado.
- `GET /cards?set={setId}&localId={n}` busca exata por set e número. Confirmado,
  devolve uma carta.
- `GET /sets/{setId}/{localId}` devolve a carta completa por set e número.
- `GET /sets/{setId}` para nome e dados do set.

Imagens. A API devolve a URL base, por exemplo
`https://assets.tcgdex.net/pt/swsh/swsh3/136`. Para carregar, acrescenta o
sufixo de qualidade e extensão. Verificado, `/high.webp`, `/high.png` e
`/low.png` respondem 200.

Forma do `pricing`. No card real, além do `pricing` de topo, existe
`variants_detailed`, um item por variante, cada um com `type`, `size` e o seu
próprio `pricing`. Os códigos de variante vêm no idioma pedido, por exemplo
`Normal` e `Reverse` em pt, contra `normal` e `reverse` em en. O mapeamento
normaliza isso para o enum `CardVariant`.

Mapeamento. O DTO do TCGdex é convertido para o modelo de domínio na borda. O
campo `pricing` vira uma lista de `CardPrice`, uma por fonte e variante.

## Valoração

Regra pura.

- `itemValue` pega o preço da variante escolhida na fonte escolhida. Se a
  variante não tiver preço naquela fonte, devolve nulo e a UI mostra "sem preço".
- `portfolioTotal` soma `itemValue * quantidade` de todos os itens.
- Fonte padrão é TCGplayer, em USD. A alternância global é da S2. Nesta fatia a
  tela da carta mostra as duas, sem converter.
- Sem série temporal. Esta fatia mostra o preço atual e nada mais.

## Persistência

Room no `:app`.

- `cards` guarda um espelho local da carta resolvida, para a coleção funcionar
  offline. Colunas principais, id, nome, setId, número, raridade, imagem,
  tipos, hp.
- `collection_items` guarda itemId, cardId, quantidade, condição, variante,
  preço pago, moeda, data de entrada.
- `card_prices` guarda o último preço por carta, fonte e variante.

O preço é atualizado quando a carta é resolvida e quando a coleção sincroniza.
Atualização periódica em background é da S3.

## Telas

- **ScanScreen.** Preview da câmera, moldura, botão de captura, botão de
  galeria. Após captura, tela de confirmação com candidatos.
- **CardDetailScreen.** Imagem, nome, set, número, raridade, hp, tipos, ataques,
  e um bloco de preço com TCGplayer USD e Cardmarket EUR. Botões de salvar.
- **CollectionScreen.** Lista ou grade com imagem, nome, set, variante, condição,
  quantidade e preço. Busca no topo. Valor total no cabeçalho.

## Estados e erros

- Sem permissão de câmera. Pede permissão, com explicação.
- OCR sem texto legível. Sugere refazer a foto com dica de enquadramento.
- Sem rede. Avisa e oferece a busca manual com a coleção offline.
- Nenhum candidato. Abre a busca manual por nome.
- Carta sem preço na fonte. Mostra "sem preço", não quebra o total.

## Testes

TDD no `:core`, com foco no que é puro.

- `OcrParser`. Casos com número com e sem barra, código promo, nome com ruído,
  HP ausente, texto vazio. Um teste por defeito plausível.
- `CandidateRanker`. Candidato certo em primeiro, empate por HP, nome parecido.
- Mapeamento de DTO para domínio. Fixture real do TCGdex, incluindo card sem
  `pricing`.
- `Valuation`. Variante sem preço, item com quantidade maior que um, total de
  coleção vazia igual a zero.
- `CardRepository`. Fakes de rede, sem chamada real.

No `:app`.

- DAO do Room com banco em memória.
- ViewModel com repositórios falsos.
- Teste de UI do fluxo de confirmação do scan.

## Critérios de aceite

1. Dado o texto de OCR de uma carta conhecida, o parser extrai nome e número
   corretos.
2. A busca devolve a carta certa como primeiro candidato no caso de teste.
3. A tela de detalhe mostra os dois preços quando existem e "sem preço" quando
   faltam.
4. Salvar cria um item persistido que sobrevive ao reinício do app.
5. O valor total da coleção é a soma dos itens, e é zero quando vazia.
6. Todos os testes do `:core` passam em JVM sem rede.

## Perguntas resolvidas na API real

1. Código de idioma pt. É `pt`. Verificado.
2. Filtros do endpoint `/cards`. Aceita `name`, `localId` e `set`, e combina
   `set` com `localId`. Verificado.
3. `pricing` em outros idiomas. Aparece igual em pt, com os mesmos valores e
   códigos de produto do en. Verificado.
4. Imagem offline. Decidido guardar só a URL nesta fatia. Cache de imagem fica
   para S3, junto com a sincronização em background.

Sem perguntas abertas. A spec está pronta para virar plano.
