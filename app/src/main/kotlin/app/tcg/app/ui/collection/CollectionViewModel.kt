package app.tcg.app.ui.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.valuation.Valuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectionUiState(
    val entries: List<CollectionEntry> = emptyList(),
    val query: String = "",
    val total: Money = Money(0.0, Currency.USD),
)

class CollectionViewModel(
    private val repository: CollectionRepository,
) : ViewModel() {

    private var allEntries: List<CollectionEntry> = emptyList()

    private val _state = MutableStateFlow(CollectionUiState())
    val state: StateFlow<CollectionUiState> = _state.asStateFlow()

    init {
        // Unconfined keeps an already-available list resolved by the time the
        // constructor returns; a real IO-backed repository still resumes later.
        viewModelScope.launch(Dispatchers.Unconfined) {
            allEntries = repository.list()
            _state.update { stateFor(allEntries, it.query) }
        }
    }

    fun onQuery(text: String) {
        _state.update { stateFor(allEntries, text) }
    }

    private fun stateFor(entries: List<CollectionEntry>, query: String) = CollectionUiState(
        entries = entries.filter { it.card.name.contains(query, ignoreCase = true) },
        query = query,
        total = Valuation.portfolioTotal(entries, PriceSource.TCGPLAYER),
    )
}
