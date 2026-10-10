package app.tcg.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.stats.CollectionStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ProfileUiState(
    val stats: CollectionStats = CollectionStats.EMPTY,
    val loading: Boolean = true,
)

class ProfileViewModel(repository: CollectionRepository) : ViewModel() {

    val state: StateFlow<ProfileUiState> = repository.observe()
        .map { entries ->
            ProfileUiState(stats = CollectionStats.compute(entries, PriceSource.TCGPLAYER), loading = false)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ProfileUiState())
}
