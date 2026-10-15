package app.tcg.app.ui.scan

import androidx.lifecycle.ViewModel
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.OcrLine
import app.tcg.core.domain.model.ParsedCardText
import app.tcg.core.domain.model.Scored
import app.tcg.core.scan.CandidateRanker
import app.tcg.core.scan.OcrParser
import app.tcg.core.scan.matchPrintedCardNumbers
import app.tcg.core.scan.parsePrintedCardNumbers
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
    val batchSearchCount: Int? = null,
    val unmatchedPrintedIds: List<String> = emptyList(),
)

class ScanViewModel(
    private val repository: CardRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()
    private var searchGeneration = 0L

    suspend fun onTextRecognized(lines: List<OcrLine>) {
        search(OcrParser.parse(lines))
    }

    suspend fun onManualSearch(name: String) {
        _state.update { it.copy(candidates = emptyList(), batchSearchCount = null, unmatchedPrintedIds = emptyList()) }
        val references = parsePrintedCardNumbers(name)
        if (references == null) {
            search(ParsedCardText(name = name, collectorNumber = null, promoCode = null, hp = null, rawText = name))
            return
        }

        val requestGeneration = ++searchGeneration
        _state.update { it.copy(loading = true, error = null, batchSearchCount = references.size) }
        val result = try {
            val cards = repository.searchByNumbers(references.map { it.localId }.toSet())
            matchPrintedCardNumbers(references, cards)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (requestGeneration != searchGeneration) return
            _state.update {
                it.copy(
                    loading = false,
                    error = "Não foi possível buscar agora. Verifique sua conexão e tente de novo.",
                    manualSearch = true,
                    batchSearchCount = references.size,
                )
            }
            return
        }
        if (requestGeneration != searchGeneration) return
        _state.update {
            it.copy(
                candidates = result.cards.map { Scored(it, 0) },
                loading = false,
                error = null,
                manualSearch = result.cards.isEmpty(),
                batchSearchCount = references.size,
                unmatchedPrintedIds = result.unmatchedReferences,
            )
        }
    }

    fun prepareForCapture() {
        searchGeneration++
        _state.value = ScanUiState()
    }

    fun onCaptureError(message: String) {
        searchGeneration++
        _state.update { it.copy(loading = false, error = message, manualSearch = true) }
    }

    private suspend fun search(parsed: ParsedCardText) {
        val requestGeneration = ++searchGeneration
        _state.update { it.copy(loading = true, error = null, batchSearchCount = null, unmatchedPrintedIds = emptyList()) }
        val collectorNumber = parsed.collectorNumber
        val name = parsed.name
        val ranked = try {
            val byName = if (name != null) repository.searchByName(name) else emptyList()
            val byNumber = if (collectorNumber != null) repository.searchByNumber(null, collectorNumber) else emptyList()
            CandidateRanker.rank(parsed, (byName + byNumber).distinctBy { it.id }).take(CANDIDATE_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (requestGeneration != searchGeneration) return
            _state.update {
                it.copy(
                    loading = false,
                    error = "Não foi possível buscar agora. Verifique sua conexão e tente de novo.",
                    manualSearch = true,
                )
            }
            return
        }
        if (requestGeneration != searchGeneration) return
        _state.update {
            it.copy(
                candidates = ranked,
                loading = false,
                error = null,
                manualSearch = ranked.isEmpty(),
            )
        }
    }

    private companion object {
        const val CANDIDATE_LIMIT = 10
    }
}
