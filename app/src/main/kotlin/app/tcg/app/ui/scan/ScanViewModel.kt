package app.tcg.app.ui.scan

import androidx.lifecycle.ViewModel
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.OcrLine
import app.tcg.core.domain.model.ParsedCardText
import app.tcg.core.domain.model.Scored
import app.tcg.core.scan.CandidateRanker
import app.tcg.core.scan.OcrParser
import app.tcg.core.scan.ScanDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ScanGridMode {
    SINGLE,
    GRID_2X2,
}

data class MultiCardResult(
    val quadrantIndex: Int, // 0: Top-Left, 1: Top-Right, 2: Bottom-Left, 3: Bottom-Right
    val parsed: ParsedCardText,
    val candidates: List<Scored<Card>>,
    val selectedCard: Card? = candidates.firstOrNull()?.value,
)

data class ScanUiState(
    val candidates: List<Scored<Card>> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val manualSearch: Boolean = false,
    val isBatchMode: Boolean = false,
    val gridMode: ScanGridMode = ScanGridMode.SINGLE,
    val batchCount: Int = 0,
    val lastAddedCard: Card? = null,
    val multiCardResults: List<MultiCardResult> = emptyList(),
    val diagnostics: ScanDiagnostics? = null,
)

class ScanViewModel(
    private val repository: CardRepository,
    private val collection: CollectionRepository? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()
    private var searchGeneration = 0L

    fun toggleBatchMode() {
        _state.update { it.copy(isBatchMode = !it.isBatchMode) }
    }

    fun setGridMode(mode: ScanGridMode) {
        _state.update {
            it.copy(
                gridMode = mode,
                candidates = emptyList(),
                multiCardResults = emptyList(),
                loading = false,
                error = null,
            )
        }
    }

    fun clearBatchCount() {
        _state.update { it.copy(batchCount = 0, lastAddedCard = null) }
    }

    suspend fun onTextRecognized(lines: List<OcrLine>) {
        search(OcrParser.parse(lines), lines)
    }

    suspend fun onMultiCardTextRecognized(quadrants: Map<Int, List<OcrLine>>) {
        val requestGeneration = ++searchGeneration
        _state.update { it.copy(loading = true, error = null) }
        val results = mutableListOf<MultiCardResult>()

        try {
            for ((quadrantIndex, lines) in quadrants) {
                if (lines.isEmpty()) continue
                val parsed = OcrParser.parse(lines)
                val name = parsed.name
                val collectorNumber = parsed.collectorNumber
                if (name == null && collectorNumber == null) continue

                val byName = if (name != null) repository.searchByName(name) else emptyList()
                val byNumber = if (collectorNumber != null) repository.searchByNumber(null, collectorNumber) else emptyList()
                val ranked = CandidateRanker.rank(parsed, (byName + byNumber).distinctBy { it.id }).take(CANDIDATE_LIMIT)
                if (ranked.isNotEmpty()) {
                    results += MultiCardResult(
                        quadrantIndex = quadrantIndex,
                        parsed = parsed,
                        candidates = ranked,
                        selectedCard = ranked.first().value,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (requestGeneration != searchGeneration) return
            _state.update {
                it.copy(
                    loading = false,
                    error = "Não foi possível buscar as cartas do bloco. Verifique sua conexão.",
                    manualSearch = true,
                )
            }
            return
        }

        if (requestGeneration != searchGeneration) return
        _state.update {
            it.copy(
                multiCardResults = results,
                loading = false,
                error = null,
                manualSearch = results.isEmpty(),
            )
        }
    }

    suspend fun onManualSearch(name: String) {
        _state.update { it.copy(candidates = emptyList(), multiCardResults = emptyList()) }
        search(ParsedCardText(name = name, collectorNumber = null, promoCode = null, hp = null, rawText = name))
    }

    fun prepareForCapture() {
        searchGeneration++
        _state.update {
            ScanUiState(
                isBatchMode = it.isBatchMode,
                gridMode = it.gridMode,
                batchCount = it.batchCount,
                lastAddedCard = it.lastAddedCard,
            )
        }
    }

    fun resetForNextScan() {
        searchGeneration++
        _state.update {
            it.copy(
                candidates = emptyList(),
                multiCardResults = emptyList(),
                loading = false,
                error = null,
                manualSearch = false,
            )
        }
    }

    fun onCaptureError(message: String) {
        searchGeneration++
        _state.update { it.copy(loading = false, error = message, manualSearch = true) }
    }

    suspend fun quickAddCard(
        card: Card,
        variant: CardVariant = CardVariant.NORMAL,
        condition: Condition = Condition.NEAR_MINT,
        quantity: Int = 1,
    ) {
        val selectedVariant = if (variant in card.variants) variant else (card.variants.firstOrNull() ?: CardVariant.NORMAL)
        collection?.add(
            card = card,
            variant = selectedVariant,
            condition = condition,
            quantity = quantity,
            pricePaid = null,
        )
        searchGeneration++
        _state.update {
            it.copy(
                batchCount = it.batchCount + quantity,
                lastAddedCard = card,
                diagnostics = it.diagnostics?.copy(chosenCardId = card.id),
                candidates = emptyList(),
                loading = false,
                error = null,
                manualSearch = false,
            )
        }
    }

    suspend fun quickAddAllMultiCards() {
        val cards = _state.value.multiCardResults.mapNotNull { it.selectedCard }
        if (cards.isEmpty()) return
        for (card in cards) {
            val variant = if (CardVariant.NORMAL in card.variants) CardVariant.NORMAL else (card.variants.firstOrNull() ?: CardVariant.NORMAL)
            collection?.add(
                card = card,
                variant = variant,
                condition = Condition.NEAR_MINT,
                quantity = 1,
                pricePaid = null,
            )
        }
        searchGeneration++
        _state.update {
            it.copy(
                batchCount = it.batchCount + cards.size,
                lastAddedCard = cards.lastOrNull(),
                multiCardResults = emptyList(),
                candidates = emptyList(),
                loading = false,
                error = null,
                manualSearch = false,
            )
        }
    }

    private suspend fun search(parsed: ParsedCardText, lines: List<OcrLine> = emptyList()) {
        val requestGeneration = ++searchGeneration
        _state.update { it.copy(loading = true, error = null) }
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
                diagnostics = ScanDiagnostics.from(parsed, lines, ranked),
            )
        }
    }

    private companion object {
        const val CANDIDATE_LIMIT = 10
    }
}
