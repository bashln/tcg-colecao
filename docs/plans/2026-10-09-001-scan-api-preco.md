# Plano de implementação, fatia 001, scan, API e preço

> **Para quem executa:** REQUIRED SUB-SKILL. Use
> superpowers:subagent-driven-development (recomendado) ou
> superpowers:executing-plans para executar tarefa por tarefa. Os passos têm
> caixas (`- [ ]`) para controle.

**Goal.** Fechar o loop essencial. Escanear carta, identificar no TCGdex,
mostrar dados e os dois preços, salvar na coleção e somar o valor.

**Architecture.** Módulo `:core` em Kotlin puro com domínio, parser, ranqueamento,
client TCGdex e valoração, testável em JVM. Módulo `:app` em Android com Compose,
CameraX, ML Kit e Room. UI chama ViewModel, ViewModel chama repositório,
repositório costura TCGdex e Room. DTO nunca vaza para o domínio.

**Tech Stack.** Kotlin 2.0.21, AGP 8.13.2, Gradle 8.14.6, Jetpack Compose,
Hilt, Room, Ktor Client, kotlinx.serialization, CameraX, ML Kit Text Recognition,
Coil.

**Spec.** `docs/specs/001-scan-api-preco.md`. Visão em `docs/vision.md`.

## Global Constraints

- TDD em todo código de produção. Sem código sem teste que falhou antes.
- Idioma `pt` em toda chamada ao TCGdex.
- Sem gráfico e sem histórico nesta fatia.
- Preços sempre nos dois mercados, TCGplayer em USD e Cardmarket em EUR.
- DTO do TCGdex nunca entra no domínio, é mapeado na borda.
- `:core` não depende de Android. Todo teste de `:core` roda em JVM sem rede.
- compileSdk 35, targetSdk 35, minSdk 26.
- Gradle 8.14.6 e AGP 8.13.2 já fixados. Não subir para a linha 9.
- Comentário só para um porquê que o código não mostra.
- Antes de cada commit, rodar `/deslop` no diff.
- Scripts de verificação não levam comentário narrando fase.

## Review Focus

Cinco entradas que a spec fixa mas nenhuma tarefa cobre sozinha, com o comportamento
esperado.

1. Texto de carta em português com acento e fonte estilizada. O parser ainda
   acha o número de colecionador.
2. Carta sem `pricing` em uma das fontes. A valoração devolve nulo e o total
   ignora o item, sem quebrar.
3. Carta promo sem barra no número, tipo `SWSH027`. O parser extrai o código.
4. Mesma carta e variante escaneada duas vezes. A segunda soma quantidade em vez
   de duplicar linha.
5. Sem rede ou sem candidato. O app oferece busca manual e não trava.

## File Structure

```
settings.gradle.kts                 módulos e repositórios
build.gradle.kts                    plugins na raiz, aplicados por módulo
gradle/libs.versions.toml           catálogo de versões
gradle.properties                   flags do AndroidX e JVM

core/
  build.gradle.kts
  src/main/kotlin/app/tcg/core/domain/model/
    Card.kt                         Card, SetInfo, Attack, CardPrice, Money
    CollectionItem.kt               CollectionItem, CollectionEntry
    ScanResult.kt                   ScanResult, ParsedCardText, OcrLine, Scored
    Enums.kt                        Condition, CardVariant, PriceSource
  src/main/kotlin/app/tcg/core/domain/valuation/Valuation.kt
  src/main/kotlin/app/tcg/core/scan/OcrParser.kt
  src/main/kotlin/app/tcg/core/scan/CandidateRanker.kt
  src/main/kotlin/app/tcg/core/data/tcgdex/
    Dto.kt                          DTOs do TCGdex
    Mappers.kt                      DTO para domínio
    TcgdexCardSource.kt             interface e impl Ktor
  src/main/kotlin/app/tcg/core/data/repository/Repositories.kt
  src/test/kotlin/app/tcg/core/...  testes por arquivo acima

app/
  build.gradle.kts
  src/main/AndroidManifest.xml
  src/main/kotlin/app/tcg/app/
    TcgApplication.kt
    MainActivity.kt
    di/AppModule.kt                 Hilt
    data/local/                     entidades, DAOs, database
    data/repository/                impl dos repositórios
    ui/scan/                        ScanScreen, ScanViewModel, CameraCapture
    ui/card/                        CardDetailScreen, CardDetailViewModel
    ui/collection/                  CollectionScreen, CollectionViewModel
    ui/nav/AppNav.kt
  src/test/kotlin/app/tcg/app/...   testes de ViewModel e DAO
```

## Interfaces centrais

Estas assinaturas são o contrato entre as tarefas. Não renomeie.

```kotlin
// :core domain
data class Money(val amount: Double, val currency: Currency)
enum class Currency { USD, EUR }
enum class PriceSource { TCGPPLAYER, CARDMARKET }
enum class CardVariant { NORMAL, HOLOFOIL, REVERSE_HOLOFOIL, FIRST_EDITION, PROMO }
enum class Condition { NEAR_MINT, LIGHTLY_PLAYED, MODERATELY_PLAYED, HEAVILY_PLAYED, DAMAGED }

data class Card(
    val id: String, val name: String, val setId: String, val setName: String,
    val collectorNumber: String, val rarity: String?, val hp: Int?,
    val types: List<String>, val attacks: List<Attack>, val imageUrl: String?,
    val variants: Set<CardVariant>, val prices: List<CardPrice>,
)
data class Attack(val name: String, val cost: List<String>, val damage: String?, val text: String?)
data class SetInfo(val id: String, val name: String, val series: String?, val releaseDate: String?)
data class CardPrice(val source: PriceSource, val variant: CardVariant, val currency: Currency,
                     val market: Double?, val low: Double?, val mid: Double?, val high: Double?,
                     val trend: Double?, val avg1: Double?, val avg7: Double?, val avg30: Double?,
                     val updatedAt: String?)
data class CollectionItem(val id: String, val cardId: String, val quantity: Int,
                          val condition: Condition, val variant: CardVariant,
                          val pricePaid: Money?, val addedAt: Long)
data class CollectionEntry(val item: CollectionItem, val card: Card)
data class OcrLine(val text: String, val top: Float, val height: Float)
data class ParsedCardText(val name: String?, val collectorNumber: String?, val promoCode: String?,
                          val hp: Int?, val rawText: String)
data class Scored<T>(val value: T, val score: Int)

// :core validation, funções puras
object Valuation {
    fun itemValue(card: Card, variant: CardVariant, source: PriceSource): Money?
    fun portfolioTotal(entries: List<CollectionEntry>, source: PriceSource): Money
}
object OcrParser { fun parse(lines: List<OcrLine>): ParsedCardText }
object CandidateRanker { fun rank(parsed: ParsedCardText, candidates: List<Card>): List<Scored<Card>> }

// :core data
interface TcgdexCardSource {
    suspend fun searchByName(name: String, language: String = "pt"): List<Card>
    suspend fun searchByNumber(setId: String?, collectorNumber: String, language: String = "pt"): List<Card>
    suspend fun getCard(id: String, language: String = "pt"): Card?
}
interface CardRepository {
    suspend fun searchByName(name: String): List<Card>
    suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card>
    suspend fun getCard(id: String): Card?
}
interface CollectionRepository {
    suspend fun add(card: Card, variant: CardVariant, condition: Condition, quantity: Int, pricePaid: Money?): CollectionItem
    suspend fun list(): List<CollectionEntry>
    suspend fun remove(itemId: String)
}
```

---

## Task 1. Scaffold multi-módulo

**Files.**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `core/build.gradle.kts`, `core/src/main/kotlin/app/tcg/core/Placeholder.kt`, `core/src/test/kotlin/app/tcg/core/PlaceholderTest.kt`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/app/tcg/app/MainActivity.kt`

**Interfaces.**
- Produces. Tasks `:core` (`./gradlew :core:test`) e `:app` (`./gradlew :app:assembleDebug`) que compilam.

- [ ] **Step 1.** Escrever `settings.gradle.kts` com `rootProject.name = "TcgPokemonCollection"`, `pluginManagement` e `dependencyResolutionManagement` do Google e Maven Central, e `include(":app", ":core")`.
- [ ] **Step 2.** Escrever `gradle/libs.versions.toml` com as versões do bloco Tech Stack. KSP deve casar com Kotlin 2.0.21, usar `2.0.21-1.0.28`. Anotar em comentário que, se a resolução falhar, subir para a menor versão disponível compatível.
- [ ] **Step 3.** Escrever o `build.gradle.kts` da raiz só com os plugins em `apply false`.
- [ ] **Step 4.** Escrever `core/build.gradle.kts` com plugins `kotlin.jvm` e `kotlin.serialization`, JVM toolchain 17, e dependências Ktor, kotlinx.serialization, coroutines, mais `testImplementation(kotlin("test"))` e `ktor-client-mock`.
- [ ] **Step 5.** Escrever o teste que ainda falha em `core/src/test/kotlin/app/tcg/core/PlaceholderTest.kt`.

```kotlin
class PlaceholderTest {
    @Test fun module_runs_tests() {
        assertEquals(2, Placeholder.one() + Placeholder.one())
    }
}
```

- [ ] **Step 6.** Rodar `./gradlew :core:test` e ver falhar por `Placeholder` inexistente.
- [ ] **Step 7.** Criar `Placeholder` com `fun one(): Int = 1`.
- [ ] **Step 8.** Rodar `./gradlew :core:test` e ver passar.
- [ ] **Step 9.** Escrever `app/build.gradle.kts` com plugins android application, kotlin android, compose, compose compiler, ksp, hilt. `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`, `buildFeatures.compose = true`. Dependências Compose BOM, Hilt, Room com KSP, CameraX, ML Kit text-recognition, Coil, coroutines.
- [ ] **Step 10.** Criar `AndroidManifest.xml` com permissão `CAMERA` e `MainActivity` mínima com `setContent { Text("ok") }`.
- [ ] **Step 11.** Rodar `./gradlew :app:assembleDebug` e ver o APK ser gerado.
- [ ] **Step 12.** Rodar `/deslop` no diff e commitar com `chore: scaffold multi-modulo core e app`.

## Task 2. Domínio e valoração

**Files.**
- Create: `core/src/main/kotlin/app/tcg/core/domain/model/Enums.kt`, `Card.kt`, `CollectionItem.kt`, `ScanResult.kt`
- Create: `core/src/main/kotlin/app/tcg/core/domain/valuation/Valuation.kt`
- Test: `core/src/test/kotlin/app/tcg/core/domain/valuation/ValuationTest.kt`

**Interfaces.**
- Produces. `Valuation.itemValue(card, variant, source): Money?` e `Valuation.portfolioTotal(entries, source): Money`.

- [ ] **Step 1.** Escrever o teste que falha.

```kotlin
class ValuationTest {
    private fun price(source: PriceSource, variant: CardVariant, market: Double?) =
        CardPrice(source, variant, if (source == PriceSource.CARDMARKET) Currency.EUR else Currency.USD,
            market, null, null, null, null, null, null, null, null)
    private fun card(vararg prices: CardPrice) = Card(
        "swsh3-136", "Furret", "swsh3", "Escuridão Incandescente", "136", "Incomum", 110,
        listOf("Incolor"), emptyList(), null, setOf(CardVariant.NORMAL, CardVariant.REVERSE_HOLOFOIL), prices.toList())

    @Test fun item_value_picks_price_for_variant_and_source() {
        val c = card(price(PriceSource.TCGPLAYER, CardVariant.NORMAL, 0.27),
                     price(PriceSource.CARDMARKET, CardVariant.NORMAL, 0.07))
        assertEquals(Money(0.27, Currency.USD), Valuation.itemValue(c, CardVariant.NORMAL, PriceSource.TCGPLAYER))
        assertEquals(Money(0.07, Currency.EUR), Valuation.itemValue(c, CardVariant.NORMAL, PriceSource.CARDMARKET))
    }

    @Test fun item_value_is_null_when_variant_missing_at_source() {
        val c = card(price(PriceSource.TCGPLAYER, CardVariant.NORMAL, 0.27))
        assertNull(Valuation.itemValue(c, CardVariant.REVERSE_HOLOFOIL, PriceSource.TCGPLAYER))
    }

    @Test fun item_value_is_null_when_market_price_absent() {
        val c = card(price(PriceSource.TCGPLAYER, CardVariant.NORMAL, null))
        assertNull(Valuation.itemValue(c, CardVariant.NORMAL, PriceSource.TCGPLAYER))
    }

    @Test fun portfolio_total_sums_item_value_times_quantity_and_carries_currency() {
        val c = card(price(PriceSource.TCGPLAYER, CardVariant.NORMAL, 2.0))
        val entries = listOf(CollectionEntry(CollectionItem("1","swsh3-136",3,Condition.NEAR_MINT,CardVariant.NORMAL,null,0L), c))
        assertEquals(Money(6.0, Currency.USD), Valuation.portfolioTotal(entries, PriceSource.TCGPLAYER))
    }

    @Test fun portfolio_total_of_empty_collection_is_zero_usd() {
        assertEquals(Money(0.0, Currency.USD), Valuation.portfolioTotal(emptyList(), PriceSource.TCGPLAYER))
    }

    @Test fun portfolio_total_ignores_items_without_price() {
        val priced = card(price(PriceSource.TCGPLAYER, CardVariant.NORMAL, 2.0))
        val unpriced = card()
        val entries = listOf(
            CollectionEntry(CollectionItem("1","a",1,Condition.NEAR_MINT,CardVariant.NORMAL,null,0L), priced),
            CollectionEntry(CollectionItem("2","b",1,Condition.NEAR_MINT,CardVariant.NORMAL,null,0L), unpriced))
        assertEquals(Money(2.0, Currency.USD), Valuation.portfolioTotal(entries, PriceSource.TCGPLAYER))
    }
}
```

- [ ] **Step 2.** Rodar `./gradlew :core:test --tests "*ValuationTest"` e ver falhar por tipos inexistentes.
- [ ] **Step 3.** Criar os modelos e enums do bloco Interfaces centrais.
- [ ] **Step 4.** Implementar `Valuation` em `Valuation.kt`. `itemValue` casa por `source` e `variant` e exige `market` não nulo. `portfolioTotal` percorre, ignora nulos, multiplica por quantidade e devolve `Money` na moeda da fonte.
- [ ] **Step 5.** Rodar os testes e ver passar. Rodar `./gradlew :core:test` inteiro.
- [ ] **Step 6.** `/deslop` e commit `feat(core): dominio e valoracao`.

## Task 3. Parser de OCR

**Files.**
- Create: `core/src/main/kotlin/app/tcg/core/scan/OcrParser.kt`
- Test: `core/src/test/kotlin/app/tcg/core/scan/OcrParserTest.kt`

**Interfaces.**
- Consumes. `OcrLine`, `ParsedCardText`.
- Produces. `OcrParser.parse(lines: List<OcrLine>): ParsedCardText`.

- [ ] **Step 1.** Escrever o teste que falha.

```kotlin
class OcrParserTest {
    private fun lines(vararg t: String) = t.mapIndexed { i, s -> OcrLine(s, i * 100f, 40f) }

    @Test fun extracts_collector_number_with_slash() {
        val r = OcrParser.parse(lines("Furret", "HP 110", "136/189"))
        assertEquals("136", r.collectorNumber)
    }

    @Test fun trims_leading_zeros_from_number() {
        assertEquals("006", OcrParser.parse(lines("Charizard ex", "006/131")).collectorNumber)
        assertEquals("6", OcrParser.parse(lines("Charizard", "06/131")).collectorNumber)
    }

    @Test fun extracts_promo_code_without_slash() {
        val r = OcrParser.parse(lines("Noctowl", "SWSH027"))
        assertNull(r.collectorNumber)
        assertEquals("SWSH027", r.promoCode)
    }

    @Test fun picks_topmost_large_text_as_name_and_drops_noise() {
        val r = OcrParser.parse(lines("BASIC", "Furret", "HP 110", "Pancada de Cauda"))
        assertEquals("Furret", r.name)
    }

    @Test fun extracts_hp() {
        assertEquals(110, OcrParser.parse(lines("Furret", "HP 110")).hp)
    }

    @Test fun returns_nulls_on_empty_input() {
        val r = OcrParser.parse(emptyList())
        assertNull(r.name); assertNull(r.collectorNumber); assertNull(r.promoCode); assertNull(r.hp)
    }

    @Test fun keeps_raw_text() {
        assertTrue(OcrParser.parse(lines("Furret", "136/189")).rawText.contains("136/189"))
    }

    @Test fun keeps_accented_name_intact() {
        assertEquals("Escuridão", OcrParser.parse(lines("Escuridão", "136/189")).name)
    }
}
```

- [ ] **Step 2.** Rodar `./gradlew :core:test --tests "*OcrParserTest"` e ver falhar.
- [ ] **Step 3.** Implementar `OcrParser`. Número por regex `\b(\d{1,3})\s*/\s*(\d{1,3})\b`, usar o primeiro grupo. Promo por `\b([A-Z]{2,4}\d{2,3})\b`. HP por `HP\s*(\d+)`. Nome é a `OcrLine` de maior `height` entre as cinco primeiras posições, descartando linhas que casem com o número, o promo, o HP, ou a lista de ruído `BASIC, STAGE 1, STAGE 2, TRAINER, ENERGY`. Faixa etária não entra.
- [ ] **Step 4.** Rodar o teste e ver passar. Depois `./gradlew :core:test`.
- [ ] **Step 5.** `/deslop` e commit `feat(core): parser de OCR`.

## Task 4. Ranqueamento de candidatos

**Files.**
- Create: `core/src/main/kotlin/app/tcg/core/scan/CandidateRanker.kt`
- Test: `core/src/test/kotlin/app/tcg/core/scan/CandidateRankerTest.kt`

**Interfaces.**
- Consumes. `ParsedCardText`, `Card`, `Scored`.
- Produces. `CandidateRanker.rank(parsed, candidates): List<Scored<Card>>`, ordenado desc por score.

- [ ] **Step 1.** Escrever o teste que falha.

```kotlin
class CandidateRankerTest {
    private fun card(id: String, name: String, number: String, hp: Int?) = Card(
        id, name, "set", "Set", number, "Rare", hp, emptyList(), emptyList(), null, emptySet(), emptyList())

    @Test fun ranks_exact_number_and_name_match_first() {
        val parsed = ParsedCardText("Furret", "136", null, 110, "")
        val result = CandidateRanker.rank(parsed, listOf(
            card("a", "Sentret", "136", 60), card("b", "Furret", "136", 110)))
        assertEquals("b", result.first().value.id)
    }

    @Test fun hp_breaks_tie_on_same_name_and_number() {
        val parsed = ParsedCardText("Charizard", "6", null, 170, "")
        val result = CandidateRanker.rank(parsed, listOf(
            card("x", "Charizard", "6", 120), card("y", "Charizard", "6", 170)))
        assertEquals("y", result.first().value.id)
    }

    @Test fun name_similarity_still_scores_when_number_missing() {
        val parsed = ParsedCardText("Furret", null, null, null, "")
        val result = CandidateRanker.rank(parsed, listOf(card("a", "Furret", "1", 110)))
        assertTrue(result.first().score > 0)
    }
}
```

- [ ] **Step 2.** Rodar `./gradlew :core:test --tests "*CandidateRankerTest"` e ver falhar.
- [ ] **Step 3.** Implementar. Score por número exato mais 4, HP exato mais 2, nome igual ignorando caixa mais 3, contém mais 1. Ordernar desc e estável.
- [ ] **Step 4.** Rodar e ver passar. Depois `./gradlew :core:test`.
- [ ] **Step 5.** `/deslop` e commit `feat(core): ranqueamento de candidatos`.

## Task 5. Client TCGdex

**Files.**
- Create: `core/src/main/kotlin/app/tcg/core/data/tcgdex/Dto.kt`, `Mappers.kt`, `TcgdexCardSource.kt`
- Create: `core/src/main/kotlin/app/tcg/core/data/repository/Repositories.kt`
- Test: `core/src/test/kotlin/app/tcg/core/data/tcgdex/TcgdexCardSourceTest.kt`
- Test resource: `core/src/test/resources/fixtures/furret.json`, `charizard.json`, `card_without_pricing.json`

**Interfaces.**
- Consumes. `Card`, `CardPrice`, `CardVariant`, `PriceSource`, `Currency`.
- Produces. `TcgdexCardSource` com os três métodos do bloco Interfaces centrais. `CardRepository` e `CollectionRepository` como interfaces.

- [ ] **Step 1.** Capturar fixtures reais. Rodar `curl -s "https://api.tcgdex.net/v2/pt/cards/swsh3-136" > core/src/test/resources/fixtures/furret.json`. Fazer o mesmo para um Charizard e um card sem `pricing`.
- [ ] **Step 2.** Escrever o teste que falha com `MockEngine` do Ktor.

```kotlin
class TcgdexCardSourceTest {
    private fun source(fixture: String, capture: (String) -> Unit = {}) : TcgdexCardSource {
        val engine = MockEngine { request -> capture(request.url.toString())
            respond(readFixture(fixture), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        return KtorTcgdexCardSource(HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } })
    }

    @Test fun get_card_maps_name_hp_and_variant_prices() = runTest {
        val card = source("furret.json").getCard("swsh3-136")!!
        assertEquals("Furret", card.name)
        assertEquals(110, card.hp)
        assertTrue(card.prices.any { it.source == PriceSource.TCGPLAYER && it.variant == CardVariant.NORMAL && it.market == 0.27 })
        assertTrue(card.prices.any { it.source == PriceSource.CARDMARKET && it.currency == Currency.EUR })
    }

    @Test fun search_by_number_uses_set_and_local_id_filters() = runTest {
        var url = ""
        source("furret.json") { url = it }.searchByNumber("swsh3", "136")
        assertTrue(url.contains("/pt/cards"))
        assertTrue(url.contains("set=swsh3"))
        assertTrue(url.contains("localId=136"))
    }

    @Test fun card_without_pricing_maps_to_empty_price_list() = runTest {
        assertTrue(source("card_without_pricing.json").getCard("x")!!.prices.isEmpty())
    }
}
```

- [ ] **Step 3.** Rodar `./gradlew :core:test --tests "*TcgdexCardSourceTest"` e ver falhar.
- [ ] **Step 4.** Implementar `Dto.kt` com `TcgdexCardDto`, `TcgdexSetDto`, `TcgdexPricingDto`, `TcgdexVariantDto`. Campo `variants_detailed` com `type` e `pricing`. Chaves desconhecidas ignoradas.
- [ ] **Step 5.** Implementar `Mappers.kt`. Normalizar o `type` da variante, `Normal` para `NORMAL`, `Reverse` para `REVERSE_HOLOFOIL`, `Holo` para `HOLOFOIL`, `FirstEdition` para `FIRST_EDITION`, `Promo` para `PROMO`, ignorando caixa. Montar `CardPrice` de TCGplayer com `marketPrice` e de Cardmarket com `avg` mais os campos `avg1`, `avg7`, `avg30`.
- [ ] **Step 6.** Implementar `KtorTcgdexCardSource : TcgdexCardSource`. Base `https://api.tcgdex.net/v2/{lang}/`. `searchByNumber` usa `GET cards?set={setId}&localId={n}` quando tem set, senão `GET cards?localId={n}`. `searchByName` usa `GET cards?name={name}`. `getCard` usa `GET cards/{id}`. Montar imagem base mais `/high.webp`.
- [ ] **Step 7.** Rodar e ver passar. Depois `./gradlew :core:test`.
- [ ] **Step 8.** `/deslop` e commit `feat(core): client TCGdex com mapeamento e preco por variante`.

## Task 6. Persistência Room e repositórios

**Files.**
- Create: `app/src/main/kotlin/app/tcg/app/data/local/Entities.kt`, `Daos.kt`, `TcgDatabase.kt`, `LocalMappers.kt`
- Create: `app/src/main/kotlin/app/tcg/app/data/repository/RoomCardRepository.kt`, `RoomCollectionRepository.kt`
- Test: `app/src/test/kotlin/app/tcg/app/data/local/DaoTest.kt`, `app/src/test/kotlin/app/tcg/app/data/repository/CollectionRepositoryTest.kt`

**Interfaces.**
- Consumes. `TcgdexCardSource`, `CardRepository`, `CollectionRepository`, `Valuation`.
- Produces. Implementações reais dos repositórios, ligadas por Hilt na Task 10.

- [ ] **Step 1.** Escrever o teste de DAO que falha, com Room em memória (`Room.inMemoryDatabaseBuilder`, `allowMainThreadQueries`).

```kotlin
@Test fun second_scan_of_same_card_and_variant_sums_quantity() = runTest {
    val dao = db.collectionDao()
    dao.upsertNew("swsh3-136", CardVariant.NORMAL, Condition.NEAR_MINT, 1)
    dao.upsertNew("swsh3-136", CardVariant.NORMAL, Condition.NEAR_MINT, 1)
    val rows = dao.listItems()
    assertEquals(1, rows.size)
    assertEquals(2, rows.first().quantity)
}
```

- [ ] **Step 2.** Rodar `./gradlew :app:testDebugUnitTest --tests "*DaoTest"` e ver falhar.
- [ ] **Step 3.** Criar entidades `CardEntity`, `CollectionItemEntity`, `CardPriceEntity`, `SetEntity` se preciso. `collection_items` tem chave única composta por `cardId`, `variant`, `condition`, para o upsert somar quantidade.
- [ ] **Step 4.** Criar DAOs com `@Upsert` e uma query que soma quantidade em conflito (`ON CONFLICT DO UPDATE SET quantity = quantity + :q`).
- [ ] **Step 5.** Criar `TcgDatabase` e o mapeamento local.
- [ ] **Step 6.** Rodar e ver passar.
- [ ] **Step 7.** Escrever o teste que falha de `RoomCollectionRepository` com um fake de `TcgdexCardSource`, cobrindo adicionar, listar com carta resolvida e remover.
- [ ] **Step 8.** Implementar os repositórios. `RoomCardRepository` busca no cache, cai para a rede e grava. `RoomCollectionRepository` grava item e resolve a carta para devolver `CollectionEntry`.
- [ ] **Step 9.** Rodar e ver passar. `./gradlew :app:testDebugUnitTest`.
- [ ] **Step 10.** `/deslop` e commit `feat(app): Room e repositorios`.

## Task 7. Feature de scan

**Files.**
- Create: `app/src/main/kotlin/app/tcg/app/ui/scan/CameraCapture.kt`, `ScanViewModel.kt`, `ScanScreen.kt`
- Test: `app/src/test/kotlin/app/tcg/app/ui/scan/ScanViewModelTest.kt`

**Interfaces.**
- Consumes. `OcrParser`, `CandidateRanker`, `CardRepository`, `TextRecognizer` do ML Kit.
- Produces. `ScanViewModel` com `state: StateFlow<ScanUiState>` e `onTextRecognized(lines: List<OcrLine>)`. `ScanUiState` tem `candidates: List<Scored<Card>>`, `loading`, `error`, `manualSearch`.

- [ ] **Step 1.** Escrever o teste que falha do ViewModel com `TextRecognizer` e `CardRepository` falsos.

```kotlin
@Test fun recognized_text_produces_ranked_candidates() = runTest {
    val repo = FakeCardRepository(listOf(card("b","Furret","136",110)))
    val vm = ScanViewModel(repo)
    vm.onTextRecognized(listOf(OcrLine("Furret",0f,40f), OcrLine("136/189",100f,40f)))
    assertEquals("b", vm.state.value.candidates.first().value.id)
}

@Test fun no_candidate_sets_manual_search_flag() = runTest {
    val vm = ScanViewModel(FakeCardRepository(emptyList()))
    vm.onTextRecognized(listOf(OcrLine("Zzz",0f,40f)))
    assertTrue(vm.state.value.manualSearch)
}
```

- [ ] **Step 2.** Rodar `./gradlew :app:testDebugUnitTest --tests "*ScanViewModelTest"` e ver falhar.
- [ ] **Step 3.** Implementar `ScanViewModel` sobre `OcrParser`, `CandidateRanker` e `CardRepository`.
- [ ] **Step 4.** Rodar e ver passar.
- [ ] **Step 5.** Implementar `CameraCapture` com CameraX, permissão e `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)`, convertendo o resultado em `List<OcrLine>`.
- [ ] **Step 6.** Implementar `ScanScreen` com moldura guia, botão de captura, contador e lista de candidatos, mais busca manual.
- [ ] **Step 7.** Verificar em dispositivo físico por `adb`, seguindo o Review Focus item 1 e 5. Registrar a evidência.
- [ ] **Step 8.** `/deslop` e commit `feat(app): scan com CameraX e ML Kit`.

## Task 8. Detalhe da carta e salvar

**Files.**
- Create: `app/src/main/kotlin/app/tcg/app/ui/card/CardDetailViewModel.kt`, `CardDetailScreen.kt`
- Test: `app/src/test/kotlin/app/tcg/app/ui/card/CardDetailViewModelTest.kt`

**Interfaces.**
- Consumes. `Valuation`, `CollectionRepository`, `Card`.
- Produces. `CardDetailViewModel` com `state` contendo os dois preços e a ação `save(variant, condition, quantity)`.

- [ ] **Step 1.** Escrever o teste que falha.

```kotlin
@Test fun shows_both_prices_and_persists_save() = runTest {
    val repo = RecordingCollectionRepository()
    val vm = CardDetailViewModel(card = cardWithBothPrices(), collection = repo)
    assertEquals(Money(0.27, Currency.USD), vm.state.value.tcgplayer)
    assertEquals(Money(0.07, Currency.EUR), vm.state.value.cardmarket)
    vm.save(CardVariant.NORMAL, Condition.NEAR_MINT, 1)
    assertEquals(1, repo.saved.size)
}

@Test fun missing_source_yields_null_price_not_crash() = runTest {
    val vm = CardDetailViewModel(cardWithOnlyTcgplayer(), RecordingCollectionRepository())
    assertNull(vm.state.value.cardmarket)
}
```

- [ ] **Step 2.** Rodar e ver falhar.
- [ ] **Step 3.** Implementar o ViewModel usando `Valuation.itemValue` por fonte.
- [ ] **Step 4.** Rodar e ver passar.
- [ ] **Step 5.** Implementar `CardDetailScreen` com imagem via Coil, dados, ataques, bloco de preço com os dois mercados, e seletores de variante, condição e quantidade, mais salvar.
- [ ] **Step 6.** Verificar em dispositivo, Review Focus item 2.
- [ ] **Step 7.** `/deslop` e commit `feat(app): detalhe da carta com precos e salvar`.

## Task 9. Lista da coleção e valor total

**Files.**
- Create: `app/src/main/kotlin/app/tcg/app/ui/collection/CollectionViewModel.kt`, `CollectionScreen.kt`
- Test: `app/src/test/kotlin/app/tcg/app/ui/collection/CollectionViewModelTest.kt`

**Interfaces.**
- Consumes. `CollectionRepository`, `Valuation`.
- Produces. `CollectionViewModel` com `state` de itens, busca e total em USD.

- [ ] **Step 1.** Escrever o teste que falha.

```kotlin
@Test fun total_is_zero_for_empty_collection() = runTest {
    val vm = CollectionViewModel(FakeCollectionRepository(emptyList()))
    assertEquals(Money(0.0, Currency.USD), vm.state.value.total)
}

@Test fun total_sums_priced_entries_and_list_renders_card() = runTest {
    val vm = CollectionViewModel(FakeCollectionRepository(listOf(entryPricedAt(2.0, qty = 3))))
    assertEquals(Money(6.0, Currency.USD), vm.state.value.total)
    assertEquals("Furret", vm.state.value.entries.first().card.name)
}

@Test fun query_filters_by_name() = runTest {
    val vm = CollectionViewModel(FakeCollectionRepository(listOf(entryNamed("Furret"), entryNamed("Charizard"))))
    vm.onQuery("fur")
    assertEquals(listOf("Furret"), vm.state.value.entries.map { it.card.name })
}
```

- [ ] **Step 2.** Rodar e ver falhar.
- [ ] **Step 3.** Implementar o ViewModel sobre `Valuation.portfolioTotal`.
- [ ] **Step 4.** Rodar e ver passar.
- [ ] **Step 5.** Implementar `CollectionScreen` em grade com imagem, nome, set, variante, condição, quantidade e preço, busca no topo e total no cabeçalho.
- [ ] **Step 6.** Verificar em dispositivo, Review Focus item 3 e 4.
- [ ] **Step 7.** `/deslop` e commit `feat(app): colecao com busca e total`.

## Task 10. Fiação do app e verificação ponta a ponta

**Files.**
- Create: `app/src/main/kotlin/app/tcg/app/TcgApplication.kt`, `di/AppModule.kt`, `ui/nav/AppNav.kt`
- Modify: `app/src/main/kotlin/app/tcg/app/MainActivity.kt`

**Interfaces.**
- Consumes. Todos os repositórios e telas.
- Produces. App navegável com Hilt ligando `TcgdexCardSource`, `TcgCardDatabase` e os repositórios.

- [ ] **Step 1.** Escrever `TcgApplication` com `@HiltAndroidApp`.
- [ ] **Step 2.** Escrever `AppModule` provendo `HttpClient`, `TcgdexCardSource`, `TcgDatabase`, DAOs, `CardRepository` e `CollectionRepository`.
- [ ] **Step 3.** Escrever `AppNav` com as rotas scan, card detail e collection.
- [ ] **Step 4.** Ligar `MainActivity` com `@AndroidEntryPoint` e o `NavHost`.
- [ ] **Step 5.** Rodar `./gradlew :core:test :app:testDebugUnitTest` e ver tudo verde.
- [ ] **Step 6.** Rodar o app no celular por `adb`. Percorrer o fluxo dos critérios de aceite 1 a 5 da spec e capturar uma tela de cada.
- [ ] **Step 7.** `./gradlew :app:assembleDebug` e confirmar o APK.
- [ ] **Step 8.** `/deslop` e commit `feat(app): fiacao Hilt, navegacao e verificacao ponta a ponta`.

## Verify geral, antes de fechar a fatia

- [ ] `./gradlew :core:test :app:testDebugUnitTest` tudo verde.
- [ ] `/deslop` no diff final.
- [ ] `/no-comments` no diff.
- [ ] Critérios de aceite da spec conferidos um a um, com a evidência anotada.
- [ ] Review Focus coberto por teste ou por verificação em dispositivo.
