package app.tcg.app.ui.home

import app.tcg.app.testutil.FakeCollectionRepository
import app.tcg.app.testutil.MainDispatcherRule
import app.tcg.app.testutil.testCard
import app.tcg.app.testutil.testEntry
import app.tcg.app.testutil.testPrice
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.CurrentHoldingsHistory
import app.tcg.core.domain.model.PriceSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val clock = Clock.fixed(Instant.parse("2026-01-10T12:00:00Z"), ZoneOffset.UTC)

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initial_state_is_loading_then_empty_collection_settles() = runTest {
        val viewModel = HomeViewModel(FakeCollectionRepository())
        assertEquals(true, viewModel.state.value.loading)

        advanceUntilIdle()

        assertEquals(false, viewModel.state.value.loading)
        assertEquals(Money(0.0, Currency.USD), viewModel.state.value.stats.totalValue)
        assertEquals(0, viewModel.state.value.stats.totalCards)
    }

    @Test
    fun stats_reflect_priced_entries_and_rank_most_valuable() = runTest {
        val viewModel = HomeViewModel(
            FakeCollectionRepository(
                listOf(
                    testEntry(testCard("a", name = "A", prices = listOf(testPrice(2.0))), qty = 3),
                    testEntry(testCard("b", name = "B", prices = listOf(testPrice(5.0)))),
                ),
            ),
        )
        advanceUntilIdle()

        val stats = viewModel.state.value.stats
        assertEquals(Money(11.0, Currency.USD), stats.totalValue)
        assertEquals(4, stats.totalCards)
        assertEquals(2, stats.distinctCards)
        assertEquals(listOf("A", "B"), stats.mostValuable.map { it.card.name })
    }

    @Test
    fun new_emission_updates_stats() = runTest {
        val repository = FakeCollectionRepository()
        val viewModel = HomeViewModel(repository)
        advanceUntilIdle()
        assertEquals(0, viewModel.state.value.stats.totalCards)

        repository.entries.value = listOf(
            testEntry(testCard("a", prices = listOf(testPrice(1.0))), qty = 2),
        )
        advanceUntilIdle()

        assertEquals(2, viewModel.state.value.stats.totalCards)
        assertEquals(Money(2.0, Currency.USD), viewModel.state.value.stats.totalValue)
    }

    @Test
    fun price_source_switches_the_history_without_changing_the_home_total() = runTest {
        val repository = FakeCollectionRepository(
            listOf(testEntry(testCard("a", prices = listOf(testPrice(2.0))), qty = 2)),
        )
        val viewModel = HomeViewModel(repository, clock)
        advanceUntilIdle()
        assertEquals(PriceSource.TCGPLAYER, viewModel.priceSource.value)
        assertEquals(Money(4.0, Currency.USD), viewModel.state.value.stats.totalValue)

        viewModel.selectPriceSource(PriceSource.CARDMARKET)
        advanceUntilIdle()

        val history = (viewModel.priceHistory.value as PriceHistoryUiState.Ready).history
        assertEquals(PriceSource.CARDMARKET, history.source)
        assertEquals(Money(4.0, Currency.USD), viewModel.state.value.stats.totalValue)
    }

    @Test
    fun history_error_can_retry_without_hiding_collection_stats() = runTest {
        val repository = FakeCollectionRepository(
            listOf(testEntry(testCard("a", prices = listOf(testPrice(2.0))))),
        )
        repository.failHistory = true
        val viewModel = HomeViewModel(repository, clock)
        advanceUntilIdle()
        assertEquals(PriceHistoryUiState.Unavailable, viewModel.priceHistory.value)
        assertEquals(1, viewModel.state.value.stats.totalCards)

        repository.failHistory = false
        viewModel.retryPriceHistory()
        advanceUntilIdle()

        assertEquals(
            CurrentHoldingsHistory(PriceSource.TCGPLAYER, java.time.LocalDate.parse("2026-01-10"), 0, emptyList(), null),
            (viewModel.priceHistory.value as PriceHistoryUiState.Ready).history,
        )
    }
}
