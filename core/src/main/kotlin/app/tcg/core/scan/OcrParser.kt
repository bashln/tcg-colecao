package app.tcg.core.scan

import app.tcg.core.domain.model.OcrLine
import app.tcg.core.domain.model.ParsedCardText

object OcrParser {
    private val NUMBER = Regex("""\b(\d{1,3})\s*/\s*(\d{1,3})\b""")
    private val PROMO = Regex("""\b([A-Z]{2,4}\d{2,3})\b""")
    private val HP = Regex("""HP\s*(\d+)""")
    private val NOISE = setOf("BASIC", "STAGE 1", "STAGE 2", "TRAINER", "ENERGY")
    private const val NAME_WINDOW = 5

    fun parse(lines: List<OcrLine>): ParsedCardText {
        val rawText = lines.joinToString("\n") { it.text }
        val collectorNumber = lines.firstNotNullOfOrNull { NUMBER.find(it.text)?.groupValues?.get(1) }
            ?.let(::normalizeCollectorNumber)
        val promoCode = lines.firstNotNullOfOrNull { PROMO.find(it.text)?.groupValues?.get(1) }
        val hp = lines.firstNotNullOfOrNull { HP.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
        return ParsedCardText(
            name = pickName(lines),
            collectorNumber = collectorNumber,
            promoCode = promoCode,
            hp = hp,
            rawText = rawText,
        )
    }

    // TCGdex local ids for 3-digit sets are printed zero-padded ("006"), so keep the
    // printed width; shorter reads are stray padding and normalize down to the number.
    private fun normalizeCollectorNumber(raw: String): String =
        if (raw.length >= 3) raw else raw.trimStart('0').ifEmpty { "0" }

    private fun pickName(lines: List<OcrLine>): String? {
        val candidates = lines.sortedBy { it.top }.take(NAME_WINDOW).filterNot { isNoise(it.text) }
        var best: OcrLine? = null
        for (line in candidates) {
            if (best == null || line.height > best.height) best = line
        }
        return best?.text
    }

    private fun isNoise(text: String): Boolean {
        val trimmed = text.trim()
        if (NUMBER.containsMatchIn(trimmed) || PROMO.containsMatchIn(trimmed) || HP.containsMatchIn(trimmed)) {
            return true
        }
        return trimmed.uppercase() in NOISE
    }
}
