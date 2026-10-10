package app.tcg.core.domain.valuation

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
