package app.tcg.app.ui.profile

import app.tcg.app.testutil.FakeCollectionRepository
import app.tcg.app.testutil.MainDispatcherRule
import app.tcg.app.testutil.testCard
import app.tcg.app.testutil.testEntry
import app.tcg.app.testutil.testPrice
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initial_state_is_loading_then_empty_collection_settles() = runTest {
        val viewModel = ProfileViewModel(FakeCollectionRepository())
        assertEquals(true, viewModel.state.value.loading)

        advanceUntilIdle()

        assertEquals(false, viewModel.state.value.loading)
        assertEquals(0, viewModel.state.value.stats.totalCards)
        assertNull(viewModel.state.value.stats.invested)
    }

    @Test
    fun stats_carry_breakdowns_and_invested_profit() = runTest {
        val viewModel = ProfileViewModel(
            FakeCollectionRepository(
                listOf(
                    testEntry(
                        testCard("a", setId = "s1", prices = listOf(testPrice(3.0))),
                        qty = 2,
                        pricePaid = Money(1.0, Currency.USD),
                    ),
                    testEntry(
                        testCard("b", setId = "s2", prices = listOf(testPrice(5.0))),
                        condition = Condition.LIGHTLY_PLAYED,
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        val stats = viewModel.state.value.stats
        assertEquals(Money(11.0, Currency.USD), stats.totalValue)
        assertEquals(3, stats.totalCards)
        assertEquals(2, stats.distinctSets)
        assertEquals(Money(1.0, Currency.USD), stats.invested)
        assertEquals(Money(10.0, Currency.USD), stats.profit)
        assertEquals(Condition.NEAR_MINT, stats.byCondition.first().key)
    }
}
