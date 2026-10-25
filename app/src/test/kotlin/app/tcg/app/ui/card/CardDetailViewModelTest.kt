package app.tcg.app.ui.card

import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.CollectionItemChanges
import app.tcg.core.domain.model.CollectionItemDetails
import app.tcg.core.domain.model.CollectionUpdate
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun saves_the_typed_price_with_the_selected_currency() = runTest {
        val repo = RecordingCollectionRepository()
        val vm = CardDetailViewModel(card = cardWithBothPrices(), collection = repo)

        vm.setPricePaidAmount("12,50")
        vm.selectPricePaidCurrency(Currency.EUR)
        vm.save(CardVariant.NORMAL, Condition.NEAR_MINT, 1)

        assertEquals(Money(12.5, Currency.EUR), repo.saved.single().pricePaid)
    }

    @Test
    fun blank_price_saves_no_price_paid() = runTest {
        val repo = RecordingCollectionRepository()
        val vm = CardDetailViewModel(card = cardWithBothPrices(), collection = repo)

        vm.save(CardVariant.NORMAL, Condition.NEAR_MINT, 1)

        assertNull(repo.saved.single().pricePaid)
    }

    @Test
    fun negative_price_is_invalid_and_saves_nothing() = runTest {
        val repo = RecordingCollectionRepository()
        val vm = CardDetailViewModel(card = cardWithBothPrices(), collection = repo)

        vm.setPricePaidAmount("-3")
        vm.save(CardVariant.NORMAL, Condition.NEAR_MINT, 1)

        assertFalse(vm.state.value.pricePaid.isValid)
        assertTrue(repo.saved.isEmpty())
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

        override suspend fun getById(itemId: String): CollectionItemDetails? = null

        override suspend fun update(original: CollectionItem, changes: CollectionItemChanges): CollectionUpdate =
            CollectionUpdate.NotFound

        override fun observe(): Flow<List<CollectionEntry>> = flowOf(emptyList())

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
