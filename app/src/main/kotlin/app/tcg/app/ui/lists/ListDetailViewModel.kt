package app.tcg.app.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tcg.core.data.repository.CardListRepository
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardListDetail
import app.tcg.core.scan.matchPrintedCardNumbers
import app.tcg.core.scan.parsePrintedCardNumbers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BatchError { INVALID_FORMAT, OFFLINE }

sealed interface BatchState {
    data object Idle : BatchState
    data object Searching : BatchState
    data class Ready(val matched: List<Card>, val unmatched: List<String>) : BatchState
    data class Failed(val reason: BatchError) : BatchState
}

enum class ListDetailMessage { CARDS_ADDED, CARD_REMOVED, ADD_FAILED }

sealed interface ListDetailUiState {
    data object Loading : ListDetailUiState
    data object NotFound : ListDetailUiState
    data class Ready(
        val detail: CardListDetail,
        val batchInput: String = "",
        val batch: BatchState = BatchState.Idle,
        val busy: Boolean = false,
        val message: ListDetailMessage? = null,
    ) : ListDetailUiState
}

class ListDetailViewModel(
    private val listId: String,
    private val lists: CardListRepository,
    private val cards: CardRepository,
) : ViewModel() {

    private data class Draft(
        val batchInput: String = "",
        val batch: BatchState = BatchState.Idle,
        val busy: Boolean = false,
        val message: ListDetailMessage? = null,
    )

    private val draft = MutableStateFlow(Draft())

    private val detailState: Flow<ListDetailUiState> =
        combine(lists.observeList(listId), draft) { detail, current ->
            if (detail == null) {
                ListDetailUiState.NotFound
            } else {
                ListDetailUiState.Ready(
                    detail = detail,
                    batchInput = current.batchInput,
                    batch = current.batch,
                    busy = current.busy,
                    message = current.message,
                )
            }
        }

    val state: StateFlow<ListDetailUiState> =
        detailState.stateIn(viewModelScope, SharingStarted.Eagerly, ListDetailUiState.Loading)

    fun updateBatchInput(value: String) {
        draft.update { it.copy(batchInput = value, batch = BatchState.Idle) }
    }

    fun runBatchSearch() {
        val current = draft.value
        if (current.batch == BatchState.Searching) return
        val parsed = parsePrintedCardNumbers(current.batchInput)
        if (parsed == null) {
            draft.update { it.copy(batch = BatchState.Failed(BatchError.INVALID_FORMAT)) }
            return
        }

        draft.update { it.copy(batch = BatchState.Searching) }
        viewModelScope.launch {
            val candidates = try {
                cards.searchByNumbers(parsed.map { it.localId }.toSet())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val next: BatchState = if (candidates == null) {
                BatchState.Failed(BatchError.OFFLINE)
            } else {
                matchPrintedCardNumbers(parsed, candidates).let { BatchState.Ready(it.cards, it.unmatchedReferences) }
            }
            // Drop a result the user has already edited past, so the dialog never shows other numbers' cards.
            draft.update { if (it.batchInput == current.batchInput) it.copy(batch = next) else it }
        }
    }

    fun addBatchMatches() {
        val current = draft.value
        val matched = (current.batch as? BatchState.Ready)?.matched.orEmpty()
        if (current.busy || matched.isEmpty()) return
        submit {
            lists.addCards(matched, setOf(listId))
            ListDetailMessage.CARDS_ADDED
        }
    }

    fun removeCard(cardId: String) {
        if (draft.value.busy) return
        submit {
            lists.removeCard(listId, cardId)
            ListDetailMessage.CARD_REMOVED
        }
    }

    fun dismissMessage() = draft.update { it.copy(message = null) }

    private fun submit(block: suspend () -> ListDetailMessage) {
        viewModelScope.launch {
            draft.update { it.copy(busy = true, message = null) }
            val outcome = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ListDetailMessage.ADD_FAILED
            }
            draft.update { it.copy(busy = false, message = outcome) }
        }
    }
}
