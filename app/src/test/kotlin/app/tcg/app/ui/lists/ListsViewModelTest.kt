package app.tcg.app.ui.lists

import app.tcg.app.testutil.FakeCardListRepository
import app.tcg.app.testutil.MainDispatcherRule
import app.tcg.core.domain.model.CardList
import app.tcg.core.domain.model.CardListKind
import app.tcg.core.domain.model.CardListSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun create_trims_the_name_and_forwards_the_kind() = runTest {
        val repository = FakeCardListRepository()
        val viewModel = ListsViewModel(repository)
        advanceUntilIdle()

        viewModel.create("  Minha lista  ", CardListKind.MISSING)
        advanceUntilIdle()

        assertEquals(listOf("Minha lista" to CardListKind.MISSING), repository.created)
    }

    @Test
    fun blank_name_surfaces_the_name_error_without_calling_the_repository() = runTest {
        val repository = FakeCardListRepository()
        val viewModel = ListsViewModel(repository)
        advanceUntilIdle()

        viewModel.create("   ", CardListKind.WANTED)
        advanceUntilIdle()

        assertEquals(ListsError.NAME_EMPTY, (viewModel.state.value as ListsUiState.Ready).error)
        assertTrue(repository.created.isEmpty())
    }

    @Test
    fun long_name_surfaces_the_length_error_without_calling_the_repository() = runTest {
        val repository = FakeCardListRepository()
        val viewModel = ListsViewModel(repository)
        advanceUntilIdle()

        viewModel.create("a".repeat(61), CardListKind.WANTED)
        advanceUntilIdle()

        assertEquals(ListsError.NAME_TOO_LONG, (viewModel.state.value as ListsUiState.Ready).error)
        assertTrue(repository.created.isEmpty())
    }

    @Test
    fun repository_failure_surfaces_a_generic_error() = runTest {
        val repository = FakeCardListRepository()
        repository.failure = IllegalStateException("disk full")
        val viewModel = ListsViewModel(repository)
        advanceUntilIdle()

        viewModel.create("Minha lista", CardListKind.WANTED)
        advanceUntilIdle()

        assertEquals(ListsError.FAILED, (viewModel.state.value as ListsUiState.Ready).error)
    }

    @Test
    fun delete_removes_the_row() = runTest {
        val repository = FakeCardListRepository(
            listOf(CardListSummary(CardList("list-1", "Quero", CardListKind.WANTED, 0L), cardCount = 2)),
        )
        val viewModel = ListsViewModel(repository)
        advanceUntilIdle()
        assertEquals(1, (viewModel.state.value as ListsUiState.Ready).lists.size)

        viewModel.delete("list-1")
        advanceUntilIdle()

        assertTrue((viewModel.state.value as ListsUiState.Ready).lists.isEmpty())
    }
}
