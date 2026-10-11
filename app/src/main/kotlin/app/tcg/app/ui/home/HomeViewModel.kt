package app.tcg.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) : ViewModel() {

    private val selectedSource = MutableStateFlow(PriceSource.TCGPLAYER)
    private val retryVersion = MutableStateFlow(0)

    val priceSource: StateFlow<PriceSource> = selectedSource

    val priceHistory: StateFlow<PriceHistoryUiState> = combine(selectedSource, retryVersion) { source, _ -> source }
        .flatMapLatest { source ->
            repository.observeCurrentHoldingsHistory(source, LocalDate.now(clock))
                .map<CurrentHoldingsHistory, PriceHistoryUiState>(PriceHistoryUiState::Ready)
                .onStart { emit(PriceHistoryUiState.Loading) }
                .catch { emit(PriceHistoryUiState.Unavailable) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PriceHistoryUiState.Loading)

    fun selectPriceSource(source: PriceSource) {
        selectedSource.value = source
    }

    fun retryPriceHistory() {
        retryVersion.value += 1
    }

    val state: StateFlow<HomeUiState> = repository.observe()
        .map { entries ->
            HomeUiState(stats = CollectionStats.compute(entries, PriceSource.TCGPLAYER), loading = false)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())
}
