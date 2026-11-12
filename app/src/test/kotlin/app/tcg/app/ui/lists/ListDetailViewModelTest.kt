package app.tcg.app.ui.lists

import app.tcg.app.testutil.FakeCardListRepository
import app.tcg.app.testutil.MainDispatcherRule
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.PriceRefreshReport
import app.tcg.core.data.repository.SetCatalogResult
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardList
import app.tcg.core.domain.model.CardListKind
import app.tcg.core.domain.model.CardListSummary
import app.tcg.core.domain.model.CardVariant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val list = CardList("list-1", "Quero", CardListKind.WANTED, 0L)

    @Test
    fun batch_search_matches_one_card_and_keeps_the_unmatched_reference() = runTest {
        val card = printedCard(id = "swsh3-21", number = "21", setTotal = 84)
        val viewModel = detailViewModel(SearchCardRepository(listOf(card)))
        advanceUntilIdle()

        viewModel.updateBatchInput("021/084, 999/084")
        viewModel.runBatchSearch()
        advanceUntilIdle()

        val batch = (viewModel.state.value as ListDetailUiState.Ready).batch
        assertEquals(listOf(card), (batch as BatchState.Ready).matched)
        assertEquals(listOf("999/084"), batch.unmatched)
    }

    @Test
    fun text_that_is_not_a_printed_number_fails_the_format_check_and_names_the_token() = runTest {
        val viewModel = detailViewModel(SearchCardRepository(emptyList()))
        advanceUntilIdle()

        viewModel.updateBatchInput("Pikachu")
        viewModel.runBatchSearch()
        advanceUntilIdle()

        val batch = (viewModel.state.value as ListDetailUiState.Ready).batch
        assertEquals(BatchState.Failed(BatchError.INVALID_FORMAT, listOf("Pikachu")), batch)
    }

    @Test
    fun a_malformed_token_does_not_discard_the_rest_of_the_batch() = runTest {
        val card = printedCard(id = "swsh3-21", number = "21", setTotal = 84)
        val viewModel = detailViewModel(SearchCardRepository(listOf(card)))
        advanceUntilIdle()

        viewModel.updateBatchInput("150, 021/084")
        viewModel.runBatchSearch()
        advanceUntilIdle()

        val batch = (viewModel.state.value as ListDetailUiState.Ready).batch as BatchState.Ready
        assertEquals(listOf(card), batch.matched)
        assertEquals(listOf("150"), batch.rejected)
        assertEquals(emptyList<String>(), batch.unmatched)
    }

    @Test
    fun add_sends_only_the_matched_cards_to_this_list() = runTest {
        val card = printedCard(id = "swsh3-21", number = "21", setTotal = 84)
        val repository = listRepository()
        val viewModel = detailViewModel(SearchCardRepository(listOf(card)), repository)
        advanceUntilIdle()
        viewModel.updateBatchInput("021/084")
        viewModel.runBatchSearch()
        advanceUntilIdle()

        viewModel.addBatchMatches()
        advanceUntilIdle()

        assertEquals(listOf(listOf(card) to setOf("list-1")), repository.added)
        assertEquals(
            ListDetailMessage.CardsAdded(1),
            (viewModel.state.value as ListDetailUiState.Ready).message,
        )
    }

    @Test
    fun adding_the_same_cards_twice_reports_zero_new_memberships() = runTest {
        val card = printedCard(id = "swsh3-21", number = "21", setTotal = 84)
        val repository = listRepository()
        val viewModel = detailViewModel(SearchCardRepository(listOf(card)), repository)
        advanceUntilIdle()
        viewModel.updateBatchInput("021/084")
        viewModel.runBatchSearch()
        advanceUntilIdle()
        viewModel.addBatchMatches()
        advanceUntilIdle()
        viewModel.dismissMessage()

        viewModel.addBatchMatches()
        advanceUntilIdle()

        assertEquals(
            ListDetailMessage.CardsAdded(0),
            (viewModel.state.value as ListDetailUiState.Ready).message,
        )
    }

    @Test
    fun remove_calls_the_repository_with_the_list_and_card() = runTest {
        val repository = listRepository()
        val viewModel = detailViewModel(SearchCardRepository(emptyList()), repository)
        advanceUntilIdle()

        viewModel.removeCard("card-1")
        advanceUntilIdle()

        assertEquals(listOf("list-1" to "card-1"), repository.removed)
    }

    private fun listRepository() = FakeCardListRepository(listOf(CardListSummary(list, cardCount = 1)))

    private fun detailViewModel(
        cards: CardRepository,
        repository: FakeCardListRepository = listRepository(),
    ) = ListDetailViewModel(list.id, repository, cards)

    private fun printedCard(id: String, number: String, setTotal: Int) = Card(
        id = id,
        name = "Furret",
        setId = "swsh3",
        setName = "Escuridão Incandescente",
        collectorNumber = number,
        rarity = "Incomum",
        hp = null,
        types = emptyList(),
        attacks = emptyList(),
        imageUrl = null,
        variants = setOf(CardVariant.NORMAL),
        prices = emptyList(),
        setTotal = setTotal,
    )

    private class SearchCardRepository(private val candidates: List<Card>) : CardRepository {
        override suspend fun searchByName(name: String): List<Card> = emptyList()
        override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> = emptyList()
        override suspend fun searchByNumbers(collectorNumbers: Set<String>): List<Card> = candidates
        override suspend fun getCard(id: String): Card? = null
        override suspend fun getSetCatalog(setId: String): SetCatalogResult = SetCatalogResult.Unavailable
        override suspend fun refreshPrices(cardIds: List<String>): PriceRefreshReport =
            PriceRefreshReport(requested = 0, refreshed = 0, unavailable = 0)
    }
}
