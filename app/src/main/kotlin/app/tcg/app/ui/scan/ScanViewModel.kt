package app.tcg.app.ui.scan

import androidx.lifecycle.ViewModel
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.OcrLine
import app.tcg.core.domain.model.ParsedCardText
import app.tcg.core.scan.CandidateRanker
import app.tcg.core.scan.OcrParser
import app.tcg.core.scan.RankedCandidate
import app.tcg.core.scan.ScanDecision
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
    val candidates: List<RankedCandidate>,
    val selectedCard: Card? = candidates.firstOrNull()?.card,
)

data class ScanUiState(
    val candidates: List<RankedCandidate> = emptyList(),
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

    /** Kept so a manual retry still reports what the camera read, not just the typed query. */
    private var lastOcrLines: List<OcrLine> = emptyList()

    fun toggleBatchMode() {
        _state.update { it.copy(isBatchMode = !it.isBatchMode) }
    }

    fun setGridMode(mode: ScanGridMode) {
        lastOcrLines = emptyList()
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
        lastOcrLines = lines
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
                // A weak quadrant has no card to quick-add, so it stays out of the batch.
                val confirmed = ScanDecision.from(ranked) as? ScanDecision.Confirmed ?: continue
                results += MultiCardResult(
                    quadrantIndex = quadrantIndex,
                    parsed = parsed,
                    candidates = confirmed.candidates,
                    selectedCard = confirmed.candidates.first().card,
                )
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
                    diagnostics = null,
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
        search(
            parsed = ParsedCardText(name = name, collectorNumber = null, promoCode = null, hp = null, rawText = name),
            lines = lastOcrLines,
            // A typed query is the user's own evidence; there is no camera reading to confirm.
            requireConfirmation = false,
        )
    }

    fun prepareForCapture() {
        searchGeneration++
        lastOcrLines = emptyList()
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
        lastOcrLines = emptyList()
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
        _state.update { it.copy(loading = false, error = message, manualSearch = true, diagnostics = null) }
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

    private suspend fun search(
        parsed: ParsedCardText,
        lines: List<OcrLine> = emptyList(),
        requireConfirmation: Boolean = true,
    ) {
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
                    diagnostics = null,
                )
            }
            return
        }
        if (requestGeneration != searchGeneration) return
        val confirmed = ScanDecision.from(ranked) as? ScanDecision.Confirmed
        val diagnostics = ScanDiagnostics.from(parsed, lines, ranked)
        if (confirmed == null && requireConfirmation) {
            _state.update {
                it.copy(
                    candidates = emptyList(),
                    loading = false,
                    error = WEAK_EVIDENCE_MESSAGE,
                    manualSearch = true,
                    diagnostics = diagnostics,
                )
            }
            return
        }
        val candidates = confirmed?.candidates ?: ranked
        _state.update {
            it.copy(
                candidates = candidates,
                loading = false,
                error = null,
                manualSearch = candidates.isEmpty(),
                diagnostics = diagnostics,
            )
        }
    }

    private companion object {
        const val CANDIDATE_LIMIT = 10

        // Distinct from the network failure message: the search worked and the reading is what
        // could not be trusted, so the user is told what to type instead.
        const val WEAK_EVIDENCE_MESSAGE =
            "Não foi possível confirmar a carta pela foto. Digite o nome ou o número da carta."
    }
}
