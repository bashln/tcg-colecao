package app.tcg.core.data.tcgdex

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.model.SetCatalog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TcgdexCardSourceTest {
    private fun source(fixture: String, capture: (String) -> Unit = {}): TcgdexCardSource {
        val engine = MockEngine { request ->
            capture(request.url.toString())
            respond(
                readFixture(fixture),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return KtorTcgdexCardSource(
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )
    }

    @Test
    fun get_card_maps_name_hp_and_variant_prices() = runTest {
        val card = source("furret.json").getCard("swsh3-136")!!
        assertEquals("Furret", card.name)
        assertEquals(110, card.hp)
        assertTrue(card.prices.any { it.source == PriceSource.TCGPLAYER && it.variant == CardVariant.NORMAL && it.market == 0.27 })
        assertTrue(card.prices.any { it.source == PriceSource.CARDMARKET && it.currency == Currency.EUR })
    }

    @Test
    fun search_by_number_uses_set_and_local_id_filters() = runTest {
        var url = ""
        source("furret.json") { url = it }.searchByNumber("swsh3", "136")
        assertTrue(url.contains("/pt/cards"))
        assertTrue(url.contains("set=swsh3"))
        assertTrue(url.contains("localId=136"))
    }

    @Test
    fun batch_search_sends_all_normalized_local_ids_in_one_request() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/cards")) {
                requested += request.url.parameters["localId"].orEmpty()
            }
            val body = """[{"id":"me05-21","localId":"021","name":"Finizen","set":{"cardCount":{"official":84}}},{"id":"me05-9","localId":"9","name":"Pawmi","set":{"cardCount":{"official":84}}}]"""
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val source = KtorTcgdexCardSource(HttpClient(engine))

        source.searchByNumbers(setOf("021", "9"))

        assertEquals(listOf("021,21,9,009"), requested)
    }

    @Test
    fun set_catalog_filters_prefix_collisions_and_duplicate_cards() = runTest {
        val body = """[
            {"id":"swsh3-136","localId":"136","name":"Furret","set":{"id":"swsh3"}},
            {"id":"swsh3.5-001","localId":"001","name":"Crobat","set":{"id":"swsh3.5"}},
            {"id":"swsh3-136","localId":"136","name":"Furret","set":{"id":"swsh3"}}
        ]"""
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += request.url.toString()
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val source = KtorTcgdexCardSource(
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )

        val catalog: SetCatalog = source.getSetCatalog("swsh3")

        assertEquals("swsh3", catalog.setId)
        assertEquals(listOf("swsh3-136"), catalog.cards.map { it.id })
        assertTrue(requested.single().contains("set=swsh3"))
    }

    @Test
    fun set_catalog_rejects_non_list_responses() = runTest {
        val engine = MockEngine {
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val source = KtorTcgdexCardSource(HttpClient(engine))

        kotlin.test.assertFails { source.getSetCatalog("swsh3") }
    }

    @Test
    fun set_catalog_rejects_empty_catalogs() = runTest {
        val engine = MockEngine {
            respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val source = KtorTcgdexCardSource(HttpClient(engine))

        kotlin.test.assertFails { source.getSetCatalog("swsh3") }
    }

    @Test
    fun search_by_number_retries_stripped_leading_zeros_after_empty_result() = runTest {
        val requested = mutableListOf<String>()
        val fixture = readFixture("furret.json")
        val engine = MockEngine { request ->
            val url = request.url.toString()
            requested += url
            val body = if (url.contains("localId=049")) "[]" else fixture
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val source = KtorTcgdexCardSource(
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )

        val cards = source.searchByNumber(null, "049")

        assertEquals("Furret", cards.single().name)
        assertTrue(requested[0].contains("localId=049"), "first query should be the given number")
        assertTrue(requested[1].contains("localId=49"), "second query should strip leading zeros")
    }

    @Test
    fun card_without_pricing_maps_to_empty_price_list() = runTest {
        assertTrue(source("card_without_pricing.json").getCard("x")!!.prices.isEmpty())
    }

    @Test
    fun card_without_tcgdex_image_falls_back_to_the_tcgplayer_product_image() = runTest {
        val card = source("promo_without_image.json").getCard("mep-081")!!

        assertEquals(
            "https://tcgplayer-cdn.tcgplayer.com/product/704879_400w.jpg",
            card.imageUrl,
        )
    }

    @Test
    fun card_with_tcgdex_image_keeps_the_tcgdex_url() = runTest {
        val card = source("furret.json").getCard("swsh3-136")!!

        assertTrue(card.imageUrl!!.startsWith("https://assets.tcgdex.net/"), card.imageUrl)
        assertTrue(card.imageUrl!!.endsWith("/high.webp"), card.imageUrl)
    }

    @Test
    fun maps_printed_set_total_from_official_count() = runTest {
        val card = source("furret.json").getCard("swsh3-136")!!
        assertEquals(189, card.setTotal)
    }

    @Test
    fun search_attaches_set_total_from_set_catalog_when_card_brief_lacks_it() = runTest {
        val brief = """[{"id":"swsh3-136","localId":"136","name":"Furret"}]"""
        val sets = """[{"id":"swsh3","name":"Escuridão Incandescente","cardCount":{"official":189,"total":201}}]"""
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath.endsWith("/sets")) sets else brief
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val source = KtorTcgdexCardSource(
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )

        val cards = source.searchByNumber(null, "136")

        assertEquals(189, cards.single().setTotal)
    }

    @Test
    fun charizard_normalizes_localized_variant_types() = runTest {
        val card = source("charizard.json").getCard("swsh4-25")!!
        assertEquals(setOf(CardVariant.NORMAL, CardVariant.HOLOFOIL, CardVariant.REVERSE_HOLOFOIL), card.variants)
        assertTrue(card.prices.any { it.source == PriceSource.TCGPLAYER && it.variant == CardVariant.NORMAL && it.market == 3.91 })
        assertTrue(card.prices.any { it.source == PriceSource.CARDMARKET && it.variant == CardVariant.HOLOFOIL })
    }

    @Test
    fun holo_variant_reads_cardmarket_holo_fields() = runTest {
        val card = source("card_holo_variant_cardmarket.json").getCard("swsh9-008")!!

        val holo = card.cardmarketPrice(CardVariant.HOLOFOIL)
        assertEquals(0.32, holo.market)
        assertEquals(0.04, holo.low)
        assertEquals(0.39, holo.trend)
        assertEquals(0.63, holo.avg1)
        assertEquals(0.36, holo.avg7)
        assertEquals(0.32, holo.avg30)
    }

    @Test
    fun holo_variant_falls_back_to_the_base_field_when_the_holo_field_is_null() = runTest {
        val card = source("card_holo_fallback_cardmarket.json").getCard("base1-4")!!

        val holo = card.cardmarketPrices(CardVariant.HOLOFOIL).first()
        assertEquals(453.87, holo.market)
        assertEquals(99.0, holo.low)
        assertEquals(123.63, holo.trend)
        assertEquals(207.4, holo.avg1)
        assertEquals(129.55, holo.avg7)
        assertEquals(202.71, holo.avg30)
    }

    @Test
    fun holo_variant_falls_back_to_the_base_field_when_the_holo_field_is_zero() = runTest {
        val card = source("card_holo_fallback_cardmarket.json").getCard("base1-4")!!

        val shadowless = card.cardmarketPrices(CardVariant.HOLOFOIL)[1]
        assertEquals(2887.65, shadowless.market)
        assertEquals(3489.25, shadowless.trend)
        assertEquals(1410.0, shadowless.avg1)
        assertEquals(2698.24, shadowless.avg7)
        assertEquals(2445.05, shadowless.avg30)
    }

    @Test
    fun normal_variant_ignores_the_cardmarket_holo_fields() = runTest {
        val card = source("card_holo_cardmarket.json").getCard("swsh3-3")!!

        val normal = card.cardmarketPrice(CardVariant.NORMAL)
        assertEquals(0.06, normal.market)
        assertEquals(0.02, normal.low)
        assertEquals(0.07, normal.trend)
        assertEquals(0.05, normal.avg1)
        assertEquals(0.05, normal.avg7)
        assertEquals(0.06, normal.avg30)
    }

    @Test
    fun reverse_holofoil_variant_ignores_the_cardmarket_holo_fields() = runTest {
        val card = source("card_holo_cardmarket.json").getCard("swsh3-3")!!

        val reverse = card.cardmarketPrice(CardVariant.REVERSE_HOLOFOIL)
        assertEquals(0.06, reverse.market)
        assertEquals(0.07, reverse.trend)
    }

    @Test
    fun zero_or_negative_cardmarket_prices_become_null() = runTest {
        val body = """{"id":"x-1","variants_detailed":[{"type":"holo","pricing":{"cardmarket":{
            "avg":0,"avg-holo":-2,"low":0,"trend":-1,"avg1":0,"avg7":-0.5,"avg30":0}}}]}"""
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val source = KtorTcgdexCardSource(
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )

        val price = source.getCard("x-1")!!.cardmarketPrice(CardVariant.HOLOFOIL)

        assertNull(price.market)
        assertNull(price.low)
        assertNull(price.trend)
        assertNull(price.avg1)
        assertNull(price.avg7)
        assertNull(price.avg30)
    }

    @Test
    fun zero_tcgplayer_market_price_becomes_null() = runTest {
        val body = """{"id":"x-2","variants_detailed":[{"type":"normal","pricing":{"tcgplayer":{
            "normal":{"productId":1,"marketPrice":0,"lowPrice":-1}}}}]}"""
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val source = KtorTcgdexCardSource(
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )

        val price = source.getCard("x-2")!!.prices.single { it.source == PriceSource.TCGPLAYER }

        assertNull(price.market)
        assertNull(price.low)
    }

    @Test
    fun search_by_number_falls_back_to_english_when_pt_returns_empty() = runTest {
        val requestedUrls = mutableListOf<String>()
        val enFixture = """[{"id":"base1-4","localId":"4","name":"Charizard","set":{"id":"base1","name":"Base Set"}}]"""
        val engine = MockEngine { request ->
            val url = request.url.toString()
            requestedUrls += url
            val body = if (url.contains("/pt/")) "[]" else enFixture
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val source = KtorTcgdexCardSource(
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )

        val cards = source.searchByNumber("base1", "4", "pt")

        assertEquals("Charizard", cards.single().name)
        assertTrue(requestedUrls.any { it.contains("/pt/cards") })
        assertTrue(requestedUrls.any { it.contains("/en/cards") })
    }

    @Test
    fun search_by_numbers_batch_falls_back_to_english_for_missing_cards() = runTest {
        val ptCards = """[{"id":"me05-21","localId":"021","name":"Finizen"}]"""
        val enCards = """[{"id":"base1-4","localId":"4","name":"Charizard"}]"""
        val engine = MockEngine { request ->
            val url = request.url.toString()
            val body = if (url.contains("/pt/")) ptCards else enCards
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val source = KtorTcgdexCardSource(
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )

        val cards = source.searchByNumbers(setOf("021", "4"), "pt")

        assertEquals(setOf("Finizen", "Charizard"), cards.map { it.name }.toSet())
    }

    @Test
    fun get_card_falls_back_to_english_on_not_found() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.contains("/pt/")) {
                respond("", HttpStatusCode.NotFound)
            } else {
                respond("""{"id":"base1-4","localId":"4","name":"Charizard"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val source = KtorTcgdexCardSource(
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )

        val card = source.getCard("base1-4", "pt")

        assertNotNull(card)
        assertEquals("Charizard", card.name)
    }

    @Test
    fun get_set_catalog_falls_back_to_english_when_pt_has_empty_card_list() = runTest {
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath.contains("/pt/")) {
                "[]"
            } else {
                """[{"id":"base1-4","localId":"4","name":"Charizard","set":{"id":"base1"}}]"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val source = KtorTcgdexCardSource(
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )

        val catalog = source.getSetCatalog("base1", "pt")

        assertEquals("base1", catalog.setId)
        assertEquals("Charizard", catalog.cards.single().name)
    }

    private fun Card.cardmarketPrices(variant: CardVariant) =
        prices.filter { it.source == PriceSource.CARDMARKET && it.variant == variant }

    private fun Card.cardmarketPrice(variant: CardVariant) =
        cardmarketPrices(variant).single()

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResource("fixtures/$name")) {
            "fixture not found: $name"
        }.readText()
}
