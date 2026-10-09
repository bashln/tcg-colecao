package app.tcg.app.ui.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.valuation.Valuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class CollectionUiState(
    val entries: List<CollectionEntry> = emptyList(),
    val query: String = "",
    val total: Money = Money(0.0, Currency.USD),
)

class CollectionViewModel(
    private val repository: CollectionRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    val state: StateFlow<CollectionUiState> =
        combine(repository.observe(), query) { entries, text -> stateFor(entries, text) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = stateFor(emptyList(), ""),
            )

    fun onQuery(text: String) {
        query.value = text
    }

    private companion object {
        fun stateFor(entries: List<CollectionEntry>, query: String) = CollectionUiState(
            entries = entries.filter { it.card.name.contains(query, ignoreCase = true) },
            query = query,
            total = Valuation.portfolioTotal(entries, PriceSource.TCGPLAYER),
        )
    }
}
