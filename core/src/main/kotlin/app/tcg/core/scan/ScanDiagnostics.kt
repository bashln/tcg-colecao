package app.tcg.core.scan

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.OcrLine
import app.tcg.core.domain.model.ParsedCardText
import app.tcg.core.domain.model.Scored
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
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        fun from(
            parsed: ParsedCardText,
            lines: List<OcrLine>,
            ranked: List<Scored<Card>>,
        ) = ScanDiagnostics(
            parsedName = parsed.name,
            collectorNumber = parsed.collectorNumber,
            setTotal = parsed.setTotal,
            hp = parsed.hp,
            promoCode = parsed.promoCode,
            ocrLines = lines.map { it.text },
            candidates = ranked.map { it.toSnapshot() },
        )
    }
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

private fun Scored<Card>.toSnapshot() = ScannedCandidate(
    id = value.id,
    name = value.name,
    collectorNumber = value.collectorNumber,
    setTotal = value.setTotal,
    hp = value.hp,
    score = score,
)
