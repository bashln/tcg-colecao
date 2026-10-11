package app.tcg.core.data.tcgdex

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

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResource("fixtures/$name")) {
            "fixture not found: $name"
        }.readText()
}
