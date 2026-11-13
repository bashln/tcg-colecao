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
    val candidates: List<ScannedCandidate>,
    val decision: String,
    val topSignals: List<String>,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        fun from(
            parsed: ParsedCardText,
            lines: List<OcrLine>,
            ranked: List<RankedCandidate>,
        ) = ScanDiagnostics(
            parsedName = parsed.name,
            collectorNumber = parsed.collectorNumber,
            setTotal = parsed.setTotal,
            hp = parsed.hp,
            promoCode = parsed.promoCode,
            ocrLines = lines.map { it.text },
            candidates = ranked.map { it.toSnapshot() },
            decision = ScanDecision.from(ranked).code,
            topSignals = ranked.firstOrNull()?.signals?.sortedBy { it.ordinal }?.map { it.name }.orEmpty(),
        )
    }
}

/** Stable strings: a device failure becomes a fixture these values can be compared against. */
private val ScanDecision.code: String
    get() = when (this) {
        is ScanDecision.Confirmed -> "CONFIRMED"
        ScanDecision.TooWeak -> "TOO_WEAK"
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
