package app.tcg.app.ui.card

import app.tcg.app.testutil.testCard
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.CollectionItemChanges
import app.tcg.core.domain.model.CollectionItemDetails
import app.tcg.core.domain.model.CollectionUpdate
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionItemEditViewModelTest {

    @Test
    fun loads_the_exact_saved_item_and_keeps_price_currency() = runTest {
        val repository = EditRepository(item(pricePaid = Money(8.25, Currency.EUR)))
        val viewModel = CollectionItemEditViewModel("owned-2", repository)

        viewModel.load()

        val state = viewModel.state.value as CollectionItemEditState.Ready
        assertEquals("owned-2", state.original.id)
        assertEquals(3, state.quantity)
        assertEquals("8.25", state.pricePaid.amount)
        assertEquals(Currency.EUR, state.pricePaid.currency)
        assertEquals("Furret", state.card?.name)
    }

    @Test
    fun duplicate_configuration_keeps_the_users_draft_and_submits_exact_item_snapshot() = runTest {
        val original = item()
        val repository = EditRepository(original, CollectionUpdate.DuplicateConfiguration)
        val viewModel = CollectionItemEditViewModel(original.id, repository)
        viewModel.load()
        viewModel.setQuantity(5)
        viewModel.selectVariant(CardVariant.REVERSE_HOLOFOIL)
        viewModel.selectCondition(Condition.LIGHTLY_PLAYED)
        viewModel.setPricePaidAmount("12,50")
        viewModel.selectPricePaidCurrency(Currency.EUR)

        assertFalse(viewModel.save())

        val state = viewModel.state.value as CollectionItemEditState.Ready
        assertEquals(5, state.quantity)
        assertEquals(CardVariant.REVERSE_HOLOFOIL, state.variant)
        assertEquals(Condition.LIGHTLY_PLAYED, state.condition)
        assertEquals(CollectionItemEditError.DUPLICATE_CONFIGURATION, state.error)
        assertEquals(original, repository.updatedOriginal)
        assertEquals(
            CollectionItemChanges(5, CardVariant.REVERSE_HOLOFOIL, Condition.LIGHTLY_PLAYED, Money(12.5, Currency.EUR)),
            repository.updatedChanges,
        )
    }

    @Test
    fun invalid_paid_price_blocks_saving_without_calling_repository() = runTest {
        val original = item()
        val repository = EditRepository(original)
        val viewModel = CollectionItemEditViewModel(original.id, repository)
        viewModel.load()
        viewModel.setPricePaidAmount("-3")

        assertFalse(viewModel.state.value.let { it as CollectionItemEditState.Ready }.pricePaidIsValid)
        assertFalse(viewModel.save())
        assertNull(repository.updatedChanges)
    }

    @Test
    fun removed_item_transitions_to_missing_instead_of_creating_a_replacement() = runTest {
        val original = item()
        val repository = EditRepository(original, CollectionUpdate.NotFound)
        val viewModel = CollectionItemEditViewModel(original.id, repository)
        viewModel.load()
        viewModel.setQuantity(2)

        assertFalse(viewModel.save())
        assertEquals(CollectionItemEditState.Missing, viewModel.state.value)
    }

    @Test
    fun removes_the_exact_item_through_the_repository() = runTest {
        val original = item()
        val repository = EditRepository(original)
        val viewModel = CollectionItemEditViewModel(original.id, repository)
        viewModel.load()

        assertTrue(viewModel.remove())

        assertEquals(original.id, repository.removedItemId)
    }

    @Test
    fun failed_removal_keeps_the_item_and_reports_the_error() = runTest {
        val original = item()
        val repository = EditRepository(original).apply { failRemove = true }
        val viewModel = CollectionItemEditViewModel(original.id, repository)
        viewModel.load()

        assertFalse(viewModel.remove())

        val state = viewModel.state.value as CollectionItemEditState.Ready
        assertEquals(CollectionItemEditError.REMOVE_FAILED, state.error)
        assertEquals(original.id, state.original.id)
    }

    private class EditRepository(
        item: CollectionItem,
        var result: CollectionUpdate = CollectionUpdate.NotFound,
    ) : CollectionRepository {
        private val entry = CollectionEntry(item, testCard(item.cardId, "Furret"))
        var updatedOriginal: CollectionItem? = null
        var updatedChanges: CollectionItemChanges? = null
        var removedItemId: String? = null
        var failRemove: Boolean = false

        override suspend fun add(
            card: Card,
            variant: CardVariant,
            condition: Condition,
            quantity: Int,
            pricePaid: Money?,
        ): CollectionItem = error("not used")

        override suspend fun list(): List<CollectionEntry> = listOf(entry)
        override suspend fun getById(itemId: String): CollectionItemDetails? =
            entry.takeIf { it.item.id == itemId }?.let { CollectionItemDetails(it.item, it.card) }

        override suspend fun update(original: CollectionItem, changes: CollectionItemChanges): CollectionUpdate {
            updatedOriginal = original
            updatedChanges = changes
            return result
        }

        override fun observe(): Flow<List<CollectionEntry>> = flowOf(listOf(entry))
        override suspend fun remove(itemId: String) {
            if (failRemove) throw IllegalStateException("removal failed")
            removedItemId = itemId
        }
    }

    private fun item(pricePaid: Money? = null) = CollectionItem(
        id = "owned-2",
        cardId = "swsh3-136",
        quantity = 3,
        condition = Condition.NEAR_MINT,
        variant = CardVariant.NORMAL,
        pricePaid = pricePaid,
        addedAt = 123L,
    )
}
