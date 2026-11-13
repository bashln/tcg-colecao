package app.tcg.core.data.jornada

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JornadaPriceSourceTest {

    @Test
    fun posts_the_contract_and_parses_variant_quotes() = runTest {
        var body = ""
        val engine = MockEngine { request ->
            assertEquals("/functions/v1/jornada-prices", request.url.encodedPath)
            assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
            assertEquals("test-key", request.headers["apikey"])
            body = (request.body as TextContent).text
            respond(
                """
                {
                  "quotes": {
                    "me02.5-161": {
                      "status": "ok",
                      "publicId": 130938,
                      "code": "me02.5-161",
                      "marketCents": 10,
                      "marketKind": "ask",
                      "fetchedAt": "2026-11-13T10:54:30.922+00:00",
                      "skus": [
                        {"condition":"near_mint","language":"PT","finish":"normal","lowestCents":14,"highestCents":30,"listings":2}
                      ],
                      "byVariant": {
                        "NORMAL": {"condition":"near_mint","language":"PT","finish":"normal","lowestCents":14,"highestCents":30,"listings":2},
                        "REVERSE_HOLOFOIL": {"condition":"near_mint","language":"PT","finish":"reverse_holo","lowestCents":40,"highestCents":60,"listings":2}
                      }
                    },
                    "30th-045": {"status": "not_found"}
                  },
                  "misses": ["30th-045"],
                  "quota": {"marketRemaining": 98, "catalogRemaining": 295}
                }
                """.trimIndent(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val source = JornadaPriceSource(HttpClient(engine), "https://example.supabase.co/", "test-key")

        val response = source.quotes(listOf(card().toJornadaRequest()), DEFAULT_MAX_AGE_HOURS)

        val quote = response.quotes.getValue("me02.5-161")
        assertEquals("ok", quote.status)
        assertEquals(10L, quote.marketCents)
        assertEquals(14L, quote.byVariant.getValue("NORMAL").lowestCents)
        assertEquals(40L, quote.byVariant.getValue("REVERSE_HOLOFOIL").lowestCents)
        assertEquals(listOf("30th-045"), response.misses)
        assertEquals(98, response.quota?.marketRemaining)
        assertTrue(body.contains("\"cardId\":\"me02.5-161\""), body)
        assertTrue(body.contains("\"maxAgeHours\":24"), body)
        assertTrue(body.contains("\"variants\":[\"NORMAL\",\"REVERSE_HOLOFOIL\"]"), body)
    }

    @Test
    fun a_non_success_status_is_a_typed_error() = runTest {
        val engine = MockEngine {
            respond("""{"error":"rate_limited"}""", HttpStatusCode.TooManyRequests)
        }
        val source = JornadaPriceSource(HttpClient(engine), "https://example.supabase.co", "test-key")

        val error = assertFailsWith<JornadaPriceException> {
            source.quotes(listOf(card().toJornadaRequest()), DEFAULT_MAX_AGE_HOURS)
        }
        assertEquals(429, error.status)
    }

    @Test
    fun a_blank_key_is_rejected_before_any_call() = runTest {
        val engine = MockEngine { error("no request expected") }
        val source = JornadaPriceSource(HttpClient(engine), "https://example.supabase.co", "")

        assertFailsWith<IllegalArgumentException> {
            source.quotes(listOf(card().toJornadaRequest()), DEFAULT_MAX_AGE_HOURS)
        }
    }

    private fun card() = Card(
        id = "me02.5-161",
        name = "Meowth da Equipe Rocket",
        setId = "me02.5",
        setName = "Fogo Branco",
        collectorNumber = "161",
        rarity = null,
        hp = null,
        types = emptyList(),
        attacks = emptyList(),
        imageUrl = null,
        variants = setOf(CardVariant.NORMAL, CardVariant.REVERSE_HOLOFOIL),
        prices = emptyList(),
    )
}
