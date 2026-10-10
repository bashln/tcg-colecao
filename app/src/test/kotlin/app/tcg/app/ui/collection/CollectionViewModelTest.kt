package app.tcg.app.ui.collection

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
import app.tcg.app.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test fun total_is_zero_for_empty_collection() = runTest {
        val vm = CollectionViewModel(FakeCollectionRepository(emptyList()))
        advanceUntilIdle()
        assertEquals(Money(0.0, Currency.USD), vm.state.value.total)
    }

    @Test fun total_sums_priced_entries_and_list_renders_card() = runTest {
        val vm = CollectionViewModel(FakeCollectionRepository(listOf(entryPricedAt(2.0, qty = 3))))
        advanceUntilIdle()
        assertEquals(Money(6.0, Currency.USD), vm.state.value.total)
        assertEquals("Furret", vm.state.value.entries.first().card.name)
    }

    @Test fun query_filters_by_name() = runTest {
        val vm = CollectionViewModel(FakeCollectionRepository(listOf(entryNamed("Furret"), entryNamed("Charizard"))))
        vm.onQuery("fur")
        advanceUntilIdle()
        assertEquals(listOf("Furret"), vm.state.value.entries.map { it.card.name })
    }

    @Test fun emitting_new_list_after_construction_updates_entries_and_total() = runTest {
        val repository = FakeCollectionRepository(emptyList())
        val vm = CollectionViewModel(repository)
        advanceUntilIdle()
        assertEquals(Money(0.0, Currency.USD), vm.state.value.total)

        repository.entries.value = listOf(entryPricedAt(2.0, qty = 3))
        advanceUntilIdle()

        assertEquals("Furret", vm.state.value.entries.first().card.name)
        assertEquals(Money(6.0, Currency.USD), vm.state.value.total)
    }

    private class FakeCollectionRepository(
        initial: List<CollectionEntry>,
    ) : CollectionRepository {
        val entries = MutableStateFlow(initial)

        override fun observe(): Flow<List<CollectionEntry>> = entries

        override suspend fun add(
            card: Card,
            variant: CardVariant,
            condition: Condition,
            quantity: Int,
            pricePaid: Money?,
        ): CollectionItem = error("not used")

        override suspend fun list(): List<CollectionEntry> = entries.value

        override suspend fun remove(itemId: String) = Unit
    }

    private fun entryPricedAt(market: Double, qty: Int) = CollectionEntry(
        item = CollectionItem(
            id = "item-furret",
            cardId = "card-Furret",
            quantity = qty,
            condition = Condition.NEAR_MINT,
            variant = CardVariant.NORMAL,
            pricePaid = null,
            addedAt = 0L,
        ),
        card = card("Furret", listOf(price(PriceSource.TCGPLAYER, CardVariant.NORMAL, market))),
    )

    private fun entryNamed(name: String) = CollectionEntry(
        item = CollectionItem(
            id = "item-$name",
            cardId = "card-$name",
            quantity = 1,
            condition = Condition.NEAR_MINT,
            variant = CardVariant.NORMAL,
            pricePaid = null,
            addedAt = 0L,
        ),
        card = card(name, emptyList()),
    )

    private fun card(name: String, prices: List<CardPrice>) = Card(
        id = "card-$name",
        name = name,
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

    private fun price(source: PriceSource, variant: CardVariant, market: Double) = CardPrice(
        source = source,
        variant = variant,
        currency = Currency.USD,
        market = market,
        low = null, mid = null, high = null, trend = null,
        avg1 = null, avg7 = null, avg30 = null, updatedAt = null,
    )
}
