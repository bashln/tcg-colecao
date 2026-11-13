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

    @Test
    fun a_valid_incoming_market_merges_field_by_field_and_keeps_the_fields_absent_from_the_payload() {
        val merged = PriceMerge.merge(
            listOf(
                price(
                    market = 10.0, low = 1.5, mid = 2.5, high = 3.5, trend = 4.5,
                    avg1 = 5.5, avg7 = 6.5, avg30 = 7.5, updatedAt = "2026-11-09",
                ),
            ),
            listOf(price(market = 12.5, low = 1.75)),
        )

        val single = merged.single()
        assertEquals(12.5, single.market)
        assertEquals(1.75, single.low)
        assertEquals(2.5, single.mid)
        assertEquals(3.5, single.high)
        assertEquals(4.5, single.trend)
        assertEquals(5.5, single.avg1)
        assertEquals(6.5, single.avg7)
        assertEquals(7.5, single.avg30)
        assertEquals("2026-11-09", single.updatedAt)
    }

    @Test
    fun a_valid_incoming_market_without_a_timestamp_keeps_the_existing_timestamp() {
        val merged = PriceMerge.merge(
            listOf(price(market = 10.0, updatedAt = "2026-11-09")),
            listOf(price(market = 12.5, updatedAt = null)),
        )

        assertEquals("2026-11-09", merged.single().updatedAt)
    }

    @Test
    fun an_existing_price_without_market_receives_the_valid_incoming_quote() {
        val merged = PriceMerge.merge(
            listOf(price(market = null, low = 0.5, updatedAt = "2026-11-09")),
            listOf(price(market = 4.2)),
        )

        assertEquals(4.2, merged.single().market)
        assertEquals(0.5, merged.single().low)
        assertEquals("2026-11-09", merged.single().updatedAt)
    }

    @Test
    fun an_existing_non_positive_market_becomes_null_when_the_payload_has_no_valid_market() {
        val zero = PriceMerge.merge(listOf(price(market = 0.0)), listOf(price(market = null)))
        val negative = PriceMerge.merge(listOf(price(market = -3.0)), listOf(price(market = 0.0)))

        assertNull(zero.single().market)
        assertNull(negative.single().market)
    }

    @Test
    fun a_non_positive_incoming_market_keeps_the_valid_market_of_the_same_key() {
        val merged = PriceMerge.merge(
            listOf(price(market = 10.0), price(market = 3.0, variant = CardVariant.HOLOFOIL)),
            listOf(price(market = 0.0), price(market = -1.0, variant = CardVariant.HOLOFOIL)),
        )

        assertEquals(10.0, merged.single { it.variant == CardVariant.NORMAL }.market)
        assertEquals(3.0, merged.single { it.variant == CardVariant.HOLOFOIL }.market)
    }

    private fun price(
        market: Double?,
        source: PriceSource = PriceSource.TCGPLAYER,
        currency: Currency = Currency.USD,
        variant: CardVariant = CardVariant.NORMAL,
        updatedAt: String? = null,
        low: Double? = null,
        mid: Double? = null,
        high: Double? = null,
        trend: Double? = null,
        avg1: Double? = null,
        avg7: Double? = null,
        avg30: Double? = null,
    ) = CardPrice(
        source = source,
        variant = variant,
        currency = currency,
        market = market,
        low = low,
        mid = mid,
        high = high,
        trend = trend,
        avg1 = avg1,
        avg7 = avg7,
        avg30 = avg30,
        updatedAt = updatedAt,
    )
}
