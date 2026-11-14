package app.tcg.core.data.jornada

import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.PriceSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JornadaMapperTest {

    @Test
    fun maps_only_the_owned_variants_that_have_a_price() {
        val quote = JornadaQuote(
            status = "ok",
            fetchedAt = "2026-11-13T10:54:30.922+00:00",
            byVariant = mapOf(
                "NORMAL" to JornadaSku(condition = "near_mint", language = "PT", finish = "normal", lowestCents = 14, highestCents = 30),
                "REVERSE_HOLOFOIL" to JornadaSku(condition = "near_mint", language = "PT", finish = "reverse_holo", lowestCents = 40, highestCents = 60),
            ),
        )

        val prices = quote.toCardPrices(setOf(CardVariant.NORMAL, CardVariant.REVERSE_HOLOFOIL, CardVariant.PROMO))

        assertEquals(2, prices.size)
        val normal = prices.first { it.variant == CardVariant.NORMAL }
        assertEquals(PriceSource.JORANDA, normal.source)
        assertEquals(Currency.BRL, normal.currency)
        assertEquals(0.14, normal.market)
        assertEquals(0.30, normal.high)
        assertEquals("near_mint", normal.condition)
        assertEquals("PT", normal.language)
        assertEquals("2026-11-13T10:54:30.922+00:00", normal.updatedAt)
        assertTrue(prices.none { it.variant == CardVariant.PROMO })
    }

    @Test
    fun a_low_liquidity_card_without_a_sku_uses_the_market_ask() {
        val quote = JornadaQuote(
            status = "ok",
            marketCents = 10,
            marketKind = "ask",
            fetchedAt = "2026-11-14T12:36:41.669Z",
        )

        val prices = quote.toCardPrices(setOf(CardVariant.NORMAL))

        assertEquals(1, prices.size)
        assertEquals(PriceSource.JORANDA, prices.single().source)
        assertEquals(Currency.BRL, prices.single().currency)
        assertEquals(0.10, prices.single().market)
        assertEquals("2026-11-14T12:36:41.669Z", prices.single().updatedAt)
    }

    @Test
    fun a_variant_without_an_active_price_is_skipped() {
        val quote = JornadaQuote(
            status = "ok",
            byVariant = mapOf(
                "NORMAL" to JornadaSku(condition = "near_mint", language = "PT", finish = "normal", lowestCents = null),
            ),
        )

        assertTrue(quote.toCardPrices(setOf(CardVariant.NORMAL)).isEmpty())
    }
}
