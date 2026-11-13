package app.tcg.core.domain.price

import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.PriceSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PriceMergeTest {
    @Test
    fun incoming_market_replaces_the_existing_price_of_the_same_key() {
        val merged = PriceMerge.merge(
            listOf(price(market = 10.0, updatedAt = "old")),
            listOf(price(market = 12.5, updatedAt = "new")),
        )

        assertEquals(listOf(12.5), merged.map { it.market })
        assertEquals(listOf("new"), merged.map { it.updatedAt })
    }

    @Test
    fun incoming_without_market_keeps_the_existing_price_and_its_timestamp() {
        val merged = PriceMerge.merge(
            listOf(price(market = 10.0, updatedAt = "old")),
            listOf(price(market = null, low = 1.0, updatedAt = "new")),
        )

        assertEquals(listOf(10.0), merged.map { it.market })
        assertEquals(listOf("old"), merged.map { it.updatedAt })
    }

    @Test
    fun zero_or_negative_market_does_not_replace_the_existing_price() {
        val merged = PriceMerge.merge(
            listOf(price(market = 10.0)),
            listOf(price(market = 0.0), price(market = -1.0, variant = CardVariant.HOLOFOIL)),
        )

        assertEquals(10.0, merged.single { it.variant == CardVariant.NORMAL }.market)
        assertNull(merged.single { it.variant == CardVariant.HOLOFOIL }.market)
    }

    @Test
    fun non_finite_market_does_not_replace_the_existing_price() {
        val merged = PriceMerge.merge(
            listOf(price(market = 10.0)),
            listOf(price(market = Double.NaN), price(market = Double.POSITIVE_INFINITY, variant = CardVariant.HOLOFOIL)),
        )

        assertEquals(10.0, merged.single { it.variant == CardVariant.NORMAL }.market)
        assertNull(merged.single { it.variant == CardVariant.HOLOFOIL }.market)
    }

    @Test
    fun existing_prices_absent_from_the_payload_survive() {
        val merged = PriceMerge.merge(
            listOf(
                price(market = 10.0),
                price(market = 3.0, variant = CardVariant.HOLOFOIL),
            ),
            emptyList(),
        )

        assertEquals(mapOf(CardVariant.NORMAL to 10.0, CardVariant.HOLOFOIL to 3.0), merged.associate { it.variant to it.market })
    }

    @Test
    fun new_key_enters_even_without_a_market() {
        val merged = PriceMerge.merge(
            listOf(price(market = 10.0)),
            listOf(price(market = null, source = PriceSource.CARDMARKET, currency = Currency.EUR)),
        )

        assertEquals(2, merged.size)
        assertNull(merged.single { it.source == PriceSource.CARDMARKET }.market)
    }

    @Test
    fun sources_with_the_same_variant_are_merged_independently() {
        val merged = PriceMerge.merge(
            listOf(price(market = 10.0), price(market = 8.0, source = PriceSource.CARDMARKET, currency = Currency.EUR)),
            listOf(price(market = 12.5), price(market = 9.0, source = PriceSource.CARDMARKET, currency = Currency.EUR)),
        )

        assertEquals(12.5, merged.single { it.source == PriceSource.TCGPLAYER }.market)
        assertEquals(9.0, merged.single { it.source == PriceSource.CARDMARKET }.market)
    }

    private fun price(
        market: Double?,
        source: PriceSource = PriceSource.TCGPLAYER,
        currency: Currency = Currency.USD,
        variant: CardVariant = CardVariant.NORMAL,
        updatedAt: String? = null,
        low: Double? = null,
    ) = CardPrice(
        source = source,
        variant = variant,
        currency = currency,
        market = market,
        low = low,
        mid = null,
        high = null,
        trend = null,
        avg1 = null,
        avg7 = null,
        avg30 = null,
        updatedAt = updatedAt,
    )
}
