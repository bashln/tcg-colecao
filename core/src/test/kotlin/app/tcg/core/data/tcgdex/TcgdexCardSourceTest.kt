package app.tcg.core.data.tcgdex

import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.PriceSource
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
    fun card_without_pricing_maps_to_empty_price_list() = runTest {
        assertTrue(source("card_without_pricing.json").getCard("x")!!.prices.isEmpty())
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
