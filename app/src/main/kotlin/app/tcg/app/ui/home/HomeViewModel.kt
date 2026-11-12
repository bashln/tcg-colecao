package app.tcg.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tcg.app.data.preferences.AppPreferences
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.CurrentHoldingsHistory
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.stats.CollectionStats
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val stats: CollectionStats = CollectionStats.EMPTY,
    val loading: Boolean = true,
)

sealed interface PriceHistoryUiState {
    data object Loading : PriceHistoryUiState
    data object Unavailable : PriceHistoryUiState
    data class Ready(val history: CurrentHoldingsHistory) : PriceHistoryUiState
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModel(
    repository: CollectionRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val preferences: AppPreferences? = null,
) : ViewModel() {

    /** Currency of the collection figures; owned by Settings so Home does not overwrite it. */
    val collectionSource: StateFlow<PriceSource> =
        preferences?.priceSource ?: MutableStateFlow(PriceSource.LIGAPOKEMON)

    /** Set only when the user picks a chip on the chart; until then the chart follows Settings. */
    private val historyChoice = MutableStateFlow<PriceSource?>(null)
    private val retryVersion = MutableStateFlow(0)

    /** Currency of the history chart. Chosen on the chart card and never written to Settings. */
    val historySource: StateFlow<PriceSource> =
        combine(collectionSource, historyChoice) { configured, chosen -> chosen ?: configured }
            .stateIn(viewModelScope, SharingStarted.Eagerly, collectionSource.value)

    val priceHistory: StateFlow<PriceHistoryUiState> = combine(historySource, retryVersion) { source, _ -> source }
        .flatMapLatest { source ->
            repository.observeCurrentHoldingsHistory(source, LocalDate.now(clock))
                .map<CurrentHoldingsHistory, PriceHistoryUiState>(PriceHistoryUiState::Ready)
                .onStart { emit(PriceHistoryUiState.Loading) }
                .catch { emit(PriceHistoryUiState.Unavailable) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PriceHistoryUiState.Loading)

    fun selectPriceSource(source: PriceSource) {
        historyChoice.value = source
    }

    fun retryPriceHistory() {
        retryVersion.value += 1
    }

    val state: StateFlow<HomeUiState> = combine(repository.observe(), collectionSource) { entries, source ->
        HomeUiState(stats = CollectionStats.compute(entries, source), loading = false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())
}
