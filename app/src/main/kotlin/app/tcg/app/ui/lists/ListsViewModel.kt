package app.tcg.app.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tcg.core.data.repository.CardListRepository
import app.tcg.core.domain.model.CardListKind
import app.tcg.core.domain.model.CardListSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ListsError { NAME_EMPTY, NAME_TOO_LONG, FAILED }

/** Mirrors the storage rule so the dialogs can flag a long name before it is submitted. */
const val CARD_LIST_NAME_MAX_LENGTH = 60

sealed interface ListsUiState {
    data object Loading : ListsUiState
    data class Ready(
        val lists: List<CardListSummary>,
        val busy: Boolean = false,
        val error: ListsError? = null,
    ) : ListsUiState
}

class ListsViewModel(private val repository: CardListRepository) : ViewModel() {

    private data class Activity(val busy: Boolean = false, val error: ListsError? = null)

    private val activity = MutableStateFlow(Activity())

    private val listsState: Flow<ListsUiState> =
        combine(repository.observeLists(), activity) { lists, current ->
            ListsUiState.Ready(lists = lists, busy = current.busy, error = current.error)
        }

    val state: StateFlow<ListsUiState> =
        listsState.stateIn(viewModelScope, SharingStarted.Eagerly, ListsUiState.Loading)

    fun create(name: String, kind: CardListKind) {
        val trimmed = validated(name) ?: return
        submit { repository.create(trimmed, kind) }
    }

    fun rename(listId: String, name: String) {
        val trimmed = validated(name) ?: return
        submit { repository.rename(listId, trimmed) }
    }

    fun delete(listId: String) = submit { repository.delete(listId) }

    fun dismissError() = activity.update { it.copy(error = null) }

    private fun validated(name: String): String? {
        val trimmed = name.trim()
        val error = when {
            trimmed.isEmpty() -> ListsError.NAME_EMPTY
            trimmed.length > CARD_LIST_NAME_MAX_LENGTH -> ListsError.NAME_TOO_LONG
            else -> null
        }
        if (error != null) activity.update { it.copy(error = error) }
        return trimmed.takeIf { error == null }
    }

    private fun submit(block: suspend () -> Unit) {
        viewModelScope.launch {
            activity.update { it.copy(busy = true, error = null) }
            val failure = try {
                block()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ListsError.FAILED
            }
            activity.update { it.copy(busy = false, error = failure) }
        }
    }
}
