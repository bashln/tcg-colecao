package app.tcg.core.scan

import app.tcg.core.domain.model.OcrLine
import app.tcg.core.domain.model.ParsedCardText

object OcrParser {
    private val SLASH_NUMBER = Regex("""(?i)(?:^|[^\d/])([0-9oO]{1,3})\s*/\s*([0-9oO]{1,4})""")
    private val ISOLATED_NUMBER = Regex("""(?i)(?:^|[\sA-Z])([0-9]{1,3})(?:[\s*★◆●▲]|\b|$)""")
    private val DAMAGE_OR_MODIFIER = Regex("""[+xX-]\s*\d+|\d+\s*[+xX-]""")
    private val YEAR = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val PROMO = Regex("""\b([A-Z]{2,4})(\d{2,3})\b""")
    private val HP = Regex("""HP\s*(\d+)""")
    private val NOISE = setOf("BASIC", "STAGE 1", "STAGE 2", "TRAINER", "ENERGY")
    private const val NAME_WINDOW = 5

    fun parse(lines: List<OcrLine>): ParsedCardText {
        val rawText = lines.joinToString("\n") { it.text }
        val collectorNumber = pickCollectorNumber(lines)
        val promoCode = lines.firstNotNullOfOrNull { line ->
            PROMO.find(line.text)?.let { match ->
                val letters = match.groupValues[1]
                // No-space HP ("HP110") matches the promo shape but is not a promo.
                if (letters == "HP") null else letters + match.groupValues[2]
            }
        }
        val hp = lines.firstNotNullOfOrNull { HP.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
        return ParsedCardText(
            name = pickName(lines),
            collectorNumber = collectorNumber,
            promoCode = promoCode,
            hp = hp,
            rawText = rawText,
        )
    }

    private fun pickCollectorNumber(lines: List<OcrLine>): String? {
        val fromSlash = lines.firstNotNullOfOrNull { line ->
            SLASH_NUMBER.find(line.text)?.let { match ->
                val raw = match.groupValues[1].replace('o', '0', ignoreCase = true)
                if (raw.all { it == '0' }) null else normalizeCollectorNumber(raw)
            }
        }
        if (fromSlash != null) return fromSlash

        if (lines.any { PROMO.containsMatchIn(it.text) }) return null

        val maxBottom = lines.maxOfOrNull { it.top + it.height } ?: 0f
        val bottomLines = if (maxBottom > 0f) {
            lines.filter { it.top >= maxBottom * 0.65f }
        } else {
            lines
        }

        for (line in bottomLines.reversed()) {
            val text = line.text.trim()
            if (isBottomNoise(text)) continue
            val match = ISOLATED_NUMBER.find(text) ?: continue
            val numStr = match.groupValues[1]
            if (numStr.all { it == '0' }) continue
            return normalizeCollectorNumber(numStr)
        }
        return null
    }

    private fun isBottomNoise(text: String): Boolean {
        if (YEAR.containsMatchIn(text) || DAMAGE_OR_MODIFIER.containsMatchIn(text) || HP.containsMatchIn(text)) return true
        val upper = text.uppercase()
        if (upper.contains("POKÉMON") || upper.contains("POKEMON") || upper.contains("NINTENDO") || upper.contains("GAME FREAK")) return true
        if (upper.contains("FRAQUEZA") || upper.contains("RESISTÊNCIA") || upper.contains("RECUO") || upper.contains("RECUAR")) return true
        if (upper.contains("REGRA") || upper.contains("PRÊMIO") || upper.contains("PREMIO")) return true
        if (upper.startsWith("LLUST") || upper.startsWith("LOST") || upper.startsWith("LUST") || upper.startsWith("ILUS")) return true
        return false
    }

    // TCGdex local ids for 3-digit sets are printed zero-padded ("006"), so keep the
    // printed width; shorter reads are stray padding and normalize down to the number.
    private fun normalizeCollectorNumber(raw: String): String =
        if (raw.length >= 3) raw else raw.trimStart('0').ifEmpty { "0" }

    private fun pickName(lines: List<OcrLine>): String? {
        val candidates = lines.sortedBy { it.top }.take(NAME_WINDOW)
            .filterNot { isNoise(it.text) || !hasNameLetters(it.text) }
        var best: OcrLine? = null
        for (line in candidates) {
            if (best == null || line.height > best.height) best = line
        }
        return best?.text
    }

    // A card name is printed as text; a large purely numeric read (e.g. the HP value
    // with no "HP" prefix) must not win the name slot.
    private fun hasNameLetters(text: String): Boolean =
        text.trim().count { it.isLetter() } >= 2

    private fun isNoise(text: String): Boolean {
        val trimmed = text.trim()
        if (SLASH_NUMBER.containsMatchIn(trimmed) || PROMO.containsMatchIn(trimmed) || HP.containsMatchIn(trimmed)) {
            return true
        }
        return trimmed.uppercase() in NOISE
    }
}
