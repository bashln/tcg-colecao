package app.tcg.core.scan

import app.tcg.core.domain.model.OcrLine
import app.tcg.core.domain.model.ParsedCardText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one scan read and what it offered, so a failure on a device can become a fixture
 * here. Nothing in it is a secret; it is the card's printed text and the app's own ranking.
 */
@Serializable
data class ScanDiagnostics(
    val parsedName: String?,
    val collectorNumber: String?,
    val setTotal: Int?,
    val hp: Int?,
    val promoCode: String?,
    val ocrLines: List<String>,
    /**
     * Box of each line in [ocrLines], same order. The parser reads text and geometry together
     * (a short name far right is dropped as the HP value, a line much shorter than the tallest is
     * out of the name's size class), so text alone cannot replay a device failure.
     */
    val ocrBoxes: List<OcrBox> = emptyList(),
    val candidates: List<ScannedCandidate>,
    val decision: ScanDecisionCode? = null,
    val topSignals: List<String> = emptyList(),
    val origin: ScanOrigin = ScanOrigin.CAMERA,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        fun from(
            parsed: ParsedCardText,
            lines: List<OcrLine>,
            ranked: List<RankedCandidate>,
            decision: ScanDecision = ScanDecision.from(ranked),
            origin: ScanOrigin = ScanOrigin.CAMERA,
        ) = ScanDiagnostics(
            parsedName = parsed.name,
            collectorNumber = parsed.collectorNumber,
            setTotal = parsed.setTotal,
            hp = parsed.hp,
            promoCode = parsed.promoCode,
            ocrLines = lines.map { it.text },
            ocrBoxes = lines.map { OcrBox(it.left, it.top, it.width, it.height) },
            candidates = ranked.map { it.toSnapshot() },
            decision = decision.code,
            topSignals = ranked.firstOrNull()?.signals?.sortedByDescending { it.weight }?.map { it.name }.orEmpty(),
            origin = origin,
        )
    }
}

@Serializable
data class OcrBox(val left: Float, val top: Float, val width: Float, val height: Float)

@Serializable
enum class ScanDecisionCode { CONFIRMED, TOO_WEAK }

@Serializable
enum class ScanOrigin { CAMERA, MANUAL }

private val ScanDecision.code: ScanDecisionCode
    get() = when (this) {
        is ScanDecision.Confirmed -> ScanDecisionCode.CONFIRMED
        ScanDecision.TooWeak -> ScanDecisionCode.TOO_WEAK
    }

@Serializable
data class ScannedCandidate(
    val id: String,
    val name: String,
    val collectorNumber: String,
    val setTotal: Int?,
    val hp: Int?,
    val score: Int,
)

private val json = Json { prettyPrint = true }

private fun RankedCandidate.toSnapshot() = ScannedCandidate(
    id = card.id,
    name = card.name,
    collectorNumber = card.collectorNumber,
    setTotal = card.setTotal,
    hp = card.hp,
    score = score,
)
