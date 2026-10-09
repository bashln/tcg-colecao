package app.tcg.app.ui.card

import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardDetailViewModelTest {

    @Test
    fun shows_both_prices_and_persists_save() = runTest {
        val repo = RecordingCollectionRepository()
        val vm = CardDetailViewModel(card = cardWithBothPrices(), collection = repo)
        assertEquals(Money(0.27, Currency.USD), vm.state.value.tcgplayer)
        assertEquals(Money(0.07, Currency.EUR), vm.state.value.cardmarket)
        vm.save(CardVariant.NORMAL, Condition.NEAR_MINT, 1)
        assertEquals(1, repo.saved.size)
    }

    @Test
    fun missing_source_yields_null_price_not_crash() = runTest {
        val vm = CardDetailViewModel(cardWithOnlyTcgplayer(), RecordingCollectionRepository())
        assertNull(vm.state.value.cardmarket)
    }

    private class RecordingCollectionRepository : CollectionRepository {
        val saved = mutableListOf<CollectionItem>()

        override suspend fun add(
            card: Card,
            variant: CardVariant,
            condition: Condition,
            quantity: Int,
            pricePaid: Money?,
        ): CollectionItem {
            val item = CollectionItem(
                id = "item-${saved.size + 1}",
                cardId = card.id,
                quantity = quantity,
                condition = condition,
                variant = variant,
                pricePaid = pricePaid,
                addedAt = 0L,
            )
            saved += item
            return item
        }

        override suspend fun list(): List<CollectionEntry> = emptyList()

        override suspend fun remove(itemId: String) = Unit
    }

    private fun cardWithBothPrices() = card(
        prices = listOf(
            price(PriceSource.TCGPLAYER, Currency.USD, 0.27),
            price(PriceSource.CARDMARKET, Currency.EUR, 0.07),
        ),
    )

    private fun cardWithOnlyTcgplayer() = card(
        prices = listOf(price(PriceSource.TCGPLAYER, Currency.USD, 0.27)),
    )

    private fun card(prices: List<CardPrice>) = Card(
        id = "swsh3-136",
        name = "Furret",
        setId = "swsh3",
        setName = "Escuridão Incandescente",
        collectorNumber = "136",
        rarity = "Incomum",
        hp = 110,
        types = listOf("Incolor"),
        attacks = emptyList(),
        imageUrl = null,
        variants = setOf(CardVariant.NORMAL),
        prices = prices,
    )

    private fun price(source: PriceSource, currency: Currency, market: Double) = CardPrice(
        source = source,
        variant = CardVariant.NORMAL,
        currency = currency,
        market = market,
        low = null, mid = null, high = null, trend = null,
        avg1 = null, avg7 = null, avg30 = null, updatedAt = null,
    )
}
