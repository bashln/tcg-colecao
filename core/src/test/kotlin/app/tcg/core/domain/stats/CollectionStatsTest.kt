package app.tcg.core.domain.stats

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
import kotlin.test.assertTrue

class CollectionStatsTest {

    @Test
    fun empty_collection_yields_zeros_and_null_money() {
        val stats = CollectionStats.compute(emptyList(), PriceSource.TCGPLAYER)
        assertEquals(Money(0.0, Currency.USD), stats.totalValue)
        assertEquals(0, stats.totalCards)
        assertEquals(0, stats.distinctCards)
        assertEquals(0, stats.distinctSets)
        assertTrue(stats.byCondition.isEmpty())
        assertTrue(stats.byVariant.isEmpty())
        assertTrue(stats.byRarity.isEmpty())
        assertTrue(stats.mostValuable.isEmpty())
        assertNull(stats.invested)
        assertNull(stats.profit)
    }

    @Test
    fun totals_sum_value_quantities_and_distinct_counts() {
        val cards = listOf(
            entry(card("a", setId = "s1", prices = listOf(price(2.0))), qty = 3),
            entry(card("b", setId = "s2", prices = listOf(price(5.0))), qty = 1),
            entry(card("a", setId = "s1", prices = listOf(price(2.0))), qty = 2, condition = Condition.LIGHTLY_PLAYED),
        )
        val stats = CollectionStats.compute(cards, PriceSource.TCGPLAYER)
        assertEquals(Money(15.0, Currency.USD), stats.totalValue)
        assertEquals(6, stats.totalCards)
        assertEquals(2, stats.distinctCards)
        assertEquals(2, stats.distinctSets)
    }

    @Test
    fun entry_without_price_counts_cards_but_adds_no_value() {
        val stats = CollectionStats.compute(
            listOf(
                entry(card("priced", prices = listOf(price(4.0))), qty = 1),
                entry(card("unpriced"), qty = 2),
            ),
            PriceSource.TCGPLAYER,
        )
        assertEquals(Money(4.0, Currency.USD), stats.totalValue)
        assertEquals(3, stats.totalCards)
        assertEquals(2, stats.distinctCards)
        assertEquals("priced", stats.mostValuable.first().card.name)
        assertEquals("unpriced", stats.mostValuable.last().card.name)
    }

    @Test
    fun breakdowns_group_a_value_and_a_count_and_order_by_count_desc() {
        val stats = CollectionStats.compute(
            listOf(
                entry(card("a", rarity = "Rara", prices = listOf(price(3.0))), qty = 3),
                entry(card("b", rarity = "Rara", prices = listOf(price(3.0))), qty = 1, condition = Condition.LIGHTLY_PLAYED),
                entry(card("c", rarity = null, prices = listOf(price(4.0))), qty = 1, condition = Condition.LIGHTLY_PLAYED),
            ),
            PriceSource.TCGPLAYER,
        )

        val nm = stats.byCondition.single { it.key == Condition.NEAR_MINT }
        assertEquals(3, nm.count)
        assertEquals(Money(9.0, Currency.USD), nm.value)
        val lp = stats.byCondition.single { it.key == Condition.LIGHTLY_PLAYED }
        assertEquals(2, lp.count)
        assertEquals(Money(7.0, Currency.USD), lp.value)
        assertEquals(Condition.NEAR_MINT, stats.byCondition.first().key)

        val normal = stats.byVariant.single { it.key == CardVariant.NORMAL }
        assertEquals(5, normal.count)
        assertEquals(Money(16.0, Currency.USD), normal.value)

        val rare = stats.byRarity.single { it.key == "Rara" }
        assertEquals(4, rare.count)
        assertEquals(Money(12.0, Currency.USD), rare.value)
        val unknown = stats.byRarity.single { it.key == null }
        assertEquals(1, unknown.count)
        assertEquals(Money(4.0, Currency.USD), unknown.value)
        assertEquals("Rara", stats.byRarity.first().key)
    }

    @Test
    fun most_valuable_ranks_by_unit_value_times_quantity_and_takes_five() {
        val entries = (1..6).map { index ->
            entry(card("card$index", prices = listOf(price(index.toDouble()))), qty = 1)
        }
        val stats = CollectionStats.compute(entries, PriceSource.TCGPLAYER)
        assertEquals(listOf("card6", "card5", "card4", "card3", "card2"), stats.mostValuable.map { it.card.name })
    }

    @Test
    fun most_valuable_uses_line_value_so_quantity_can_outrank_unit_price() {
        val stats = CollectionStats.compute(
            listOf(
                entry(card("bulk", prices = listOf(price(1.0))), qty = 10),
                entry(card("single", prices = listOf(price(4.0))), qty = 1),
            ),
            PriceSource.TCGPLAYER,
        )
        assertEquals(listOf("bulk", "single"), stats.mostValuable.map { it.card.name })
    }

    @Test
    fun invested_and_profit_appear_only_when_a_price_paid_exists() {
        val none = CollectionStats.compute(
            listOf(entry(card("a", prices = listOf(price(4.0))), qty = 1)),
            PriceSource.TCGPLAYER,
        )
        assertNull(none.invested)
        assertNull(none.profit)

        val priced = CollectionStats.compute(
            listOf(
                entry(card("a", prices = listOf(price(4.0))), qty = 1, pricePaid = Money(1.0, Currency.USD)),
                entry(card("b", prices = listOf(price(1.0))), qty = 1, pricePaid = Money(2.0, Currency.USD)),
            ),
            PriceSource.TCGPLAYER,
        )
        assertEquals(Money(3.0, Currency.USD), priced.invested)
        assertEquals(Money(2.0, Currency.USD), priced.profit)
    }

    @Test
    fun uses_the_requested_price_source() {
        val card = card(
            "a",
            prices = listOf(
                price(2.0, source = PriceSource.TCGPLAYER, currency = Currency.USD),
                price(9.0, source = PriceSource.CARDMARKET, currency = Currency.EUR),
            ),
        )
        val stats = CollectionStats.compute(listOf(entry(card, qty = 1)), PriceSource.CARDMARKET)
        assertEquals(Money(9.0, Currency.EUR), stats.totalValue)
    }

    private fun entry(
        card: Card,
        qty: Int = 1,
        condition: Condition = Condition.NEAR_MINT,
        variant: CardVariant = CardVariant.NORMAL,
        pricePaid: Money? = null,
    ) = CollectionEntry(
        item = CollectionItem(
            id = "item-${card.id}-$condition-$variant",
            cardId = card.id,
            quantity = qty,
            condition = condition,
            variant = variant,
            pricePaid = pricePaid,
            addedAt = 0L,
        ),
        card = card,
    )

    private fun card(
        id: String,
        setId: String = "set-$id",
        rarity: String? = "Comum",
        prices: List<CardPrice> = emptyList(),
    ) = Card(
        id = id,
        name = id,
        setId = setId,
        setName = "Set $setId",
        collectorNumber = "1",
        rarity = rarity,
        hp = null,
        types = emptyList(),
        attacks = emptyList(),
        imageUrl = null,
        variants = setOf(CardVariant.NORMAL),
        prices = prices,
    )

    private fun price(
        market: Double,
        variant: CardVariant = CardVariant.NORMAL,
        source: PriceSource = PriceSource.TCGPLAYER,
        currency: Currency = Currency.USD,
    ) = CardPrice(
        source = source,
        variant = variant,
        currency = currency,
        market = market,
        low = null,
        mid = null,
        high = null,
        trend = null,
        avg1 = null,
        avg7 = null,
        avg30 = null,
        updatedAt = null,
    )
}
