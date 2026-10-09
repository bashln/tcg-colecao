package app.tcg.app.ui.scan

import androidx.lifecycle.ViewModel
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.OcrLine
import app.tcg.core.domain.model.ParsedCardText
import app.tcg.core.domain.model.Scored
import app.tcg.core.scan.CandidateRanker
import app.tcg.core.scan.OcrParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ScanUiState(
    val candidates: List<Scored<Card>> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val manualSearch: Boolean = false,
)

class ScanViewModel(
    private val repository: CardRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    suspend fun onTextRecognized(lines: List<OcrLine>) {
        search(OcrParser.parse(lines))
    }

    suspend fun onManualSearch(name: String) {
        search(ParsedCardText(name = name, collectorNumber = null, promoCode = null, hp = null, rawText = name))
    }

    fun onCaptureError(message: String) {
        _state.update { it.copy(loading = false, error = message, manualSearch = true) }
    }

    private suspend fun search(parsed: ParsedCardText) {
        _state.update { it.copy(loading = true, error = null) }
        val collectorNumber = parsed.collectorNumber
        val name = parsed.name
        val candidates = try {
            when {
                collectorNumber != null -> repository.searchByNumber(null, collectorNumber)
                name != null -> repository.searchByName(name)
                else -> emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    error = "Falha na busca. Tente a busca manual.",
                    manualSearch = true,
                )
            }
            return
        }
        val ranked = CandidateRanker.rank(parsed, candidates)
        _state.update {
            it.copy(
                candidates = ranked,
                loading = false,
                error = null,
                manualSearch = ranked.isEmpty(),
            )
        }
    }
}
