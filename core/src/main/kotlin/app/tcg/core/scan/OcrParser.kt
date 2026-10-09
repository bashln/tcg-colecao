package app.tcg.core.scan

import app.tcg.core.domain.model.OcrLine
import app.tcg.core.domain.model.ParsedCardText

object OcrParser {
    private val SLASH_NUMBER = Regex("""(?i)(?:^|[^\d/])([0-9oO]{1,3})\s*[/|]\s*([0-9oO]{1,4})""")
    private val CONCAT_COLLECTOR_NUMBER = Regex("""(?i)\b([0-9]{2,3})([0-9]{3})\b""")
    private val ISOLATED_NUMBER = Regex("""(?i)(?:^|[\sA-Z])([0-9]{1,3})(?:[\s*★◆●▲]|\b|$)""")
    private val DAMAGE_OR_MODIFIER = Regex("""[+xX-]\s*\d+|\d+\s*[+xX-]""")
    private val YEAR = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val PROMO = Regex("""\b([A-Z]{2,4})(\d{2,3})\b""")
    private val HP_EXTRACTION = Regex("""(?i)\b(?:HP|PS|PV|PL|P\$)\s*(\d+)""")
    private val HP_REGEX = Regex("""(?i)\b(HP|PS|PV|PL|P\$)\s*\d+""")
    private val HP_SOLO_REGEX = Regex("""(?i)^\s*[\W_A-Z]{0,2}\s*\d{2,3}\s*[A-Z]?\s*$""")
    private val STAGE_WORDS = setOf(
        "BASIC", "STAGE 1", "STAGE 2", "TRAINER", "ENERGY",
        "BÁSICO", "BASICO", "ESTÁGIO 1", "ESTAGIO 1", "ESTÁGIO 2", "ESTAGIO 2",
        "ESTÁGIO1", "ESTAGIO1", "ESTÁGIO2", "ESTAGIO2", "E5TÁGIO1", "STÁGIO", "GASICO", "ASICO", "BASILD",
    )
    private val STAGE_PREFIX = Regex("""(?i)^([BEG80]?ASIC[OAD0-9]?|E?[5S]T[ÁA]GIO[L12DIO0-9]?|STAGE\s*[12]?)[):.\-_]?\s*""")
    private val REGULATION_MARK_PREFIX = Regex("""^[A-Z]\s+(?=[A-Za-z]{2})""")
    private val TRAILING_HP = Regex("""\s*\d{2,3}$""")
    private val SUFFIX_SPACE = Regex("""([a-z])(V|ex)\b""")

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
        val hp = lines.firstNotNullOfOrNull { HP_EXTRACTION.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
        return ParsedCardText(
            name = pickName(lines),
            collectorNumber = collectorNumber,
            promoCode = promoCode,
            hp = hp,
            rawText = rawText,
            setTotal = pickSetTotal(lines),
        )
    }

    // The denominator of the printed "NNN/TTT" is the set size as printed, the one signal
    // that separates same-name, same-number cards from different sets.
    private fun pickSetTotal(lines: List<OcrLine>): Int? {
        val match = lines.firstNotNullOfOrNull { SLASH_NUMBER.find(it.text) } ?: return null
        return match.groupValues[2].replace('o', '0', ignoreCase = true).toIntOrNull()
    }

    private fun pickCollectorNumber(lines: List<OcrLine>): String? {
        val fromSlash = lines.firstNotNullOfOrNull { line ->
            SLASH_NUMBER.find(line.text)?.let { match ->
                val raw = match.groupValues[1].replace('o', '0', ignoreCase = true)
                // When "1" fuses with slash (e.g. "o0/196" instead of "001/196"), recover as "001"
                val numStr = if (raw.all { it == '0' }) "001" else raw
                normalizeCollectorNumber(numStr)
            }
        }
        if (fromSlash != null) return fromSlash

        if (lines.any { PROMO.containsMatchIn(it.text) }) return null

        val maxBottom = lines.maxOfOrNull { it.top + it.height } ?: 0f
        val bottomLines = if (maxBottom > 0f) {
            lines.filter { it.top >= maxBottom * 0.78f }
        } else {
            lines
        }

        for (line in bottomLines.reversed()) {
            val text = line.text.trim()
            if (isBottomNoise(text)) continue
            CONCAT_COLLECTOR_NUMBER.find(text)?.let { match ->
                val numStr = match.groupValues[1]
                return normalizeCollectorNumber(numStr)
            }
            val match = ISOLATED_NUMBER.find(text) ?: continue
            val numStr = match.groupValues[1]
            if (numStr.all { it == '0' }) continue
            return normalizeCollectorNumber(numStr)
        }
        return null
    }

    private fun isBottomNoise(text: String): Boolean {
        if (YEAR.containsMatchIn(text) || DAMAGE_OR_MODIFIER.containsMatchIn(text) || HP_EXTRACTION.containsMatchIn(text)) return true
        val upper = text.uppercase()
        if (upper.contains("POKÉMON") || upper.contains("POKEMON") || upper.contains("NINTENDO") || upper.contains("GAME FREAK")) return true
        if (upper.contains("FRAQUEZA") || upper.contains("RESISTÊNCIA") || upper.contains("RECUO") || upper.contains("RECUAR")) return true
        if (upper.contains("REGRA") || upper.contains("PRÊMIO") || upper.contains("PREMIO")) return true
        if (upper.startsWith("LLUST") || upper.startsWith("LOST") || upper.startsWith("LUST") || upper.startsWith("ILUS")) return true
        if (upper.contains("APOIADOR") || upper.contains("SUPPORTER") || upper.contains("BARALHO") || upper.contains("DECK")) return true
        return false
    }

    // TCGdex local ids for 3-digit sets are printed zero-padded ("006"), so keep the
    // printed width; shorter reads are stray padding and normalize down to the number.
    private fun normalizeCollectorNumber(raw: String): String =
        if (raw.length >= 3) raw else raw.trimStart('0').ifEmpty { "0" }

    private fun pickName(lines: List<OcrLine>): String? {
        if (lines.isEmpty()) return null
        val candidates = lines
            .filterNot { isNoise(it.text) || isHpLine(it) || isLabel(it.text) }
            .mapNotNull { line ->
                val cleaned = cleanNameCandidate(line.text)
                if (hasNameLetters(cleaned)) line to cleaned else null
            }

        if (candidates.isEmpty()) return null

        // The name is the largest text on the card, but not always the absolute tallest OCR box:
        // attack and ability lines can measure slightly taller. Treat lines within 40% of the
        // tallest candidate as the same size class and take the topmost, so a lower attack loses
        // to the name. No position cutoff, so a card that does not fill the frame still works.
        val tallest = candidates.maxOf { it.first.height }
        val best = candidates
            .filter { it.first.height >= tallest * 0.6f }
            .minByOrNull { it.first.top }
            ?.second ?: return null

        return normalizeCardName(best, lines)
    }

    // Stage/type labels (BASICO, ESTAGIO, TREINADOR, ENERGIA and their OCR variants) are short
    // all-caps tokens printed above the name and must not be mistaken for it.
    private fun isLabel(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.length in 2..14 && letters.none { it.isLowerCase() }
    }

    private fun cleanNameCandidate(text: String): String {
        var s = STAGE_PREFIX.replace(text.trim(), "").trim()
        s = REGULATION_MARK_PREFIX.replace(s, "").trim()
        s = TRAILING_HP.replace(s, "").trim()
        return s
    }

    private fun normalizeCardName(name: String, allLines: List<OcrLine>): String {
        var s = name
        s = SUFFIX_SPACE.replace(s) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }
        s = s.replace(Regex("""\s+X\b"""), " GX")
        s = s.replace(Regex("""(?<=[a-z])X\b"""), " GX")

        val ruleText = allLines.joinToString(" ") { it.text }
        val isVmax = Regex("""(?i)Regra.*VMAX|Quando seu Pokémon VMAX\b|\bVMAX\b""").containsMatchIn(ruleText)
        if (isVmax) {
            s = s.replace(Regex("""(?i)\s*(VMAX|Vx|VM|V)$"""), "").trim()
            s = "$s VMAX"
        } else if (Regex("""(?i)Regra.*GX|ALIADOS|ataque\s*GX|\bGX\b""").containsMatchIn(ruleText)) {
            s = s.replace(Regex("""(?i)\s*GX$"""), "").trim()
            s = "$s GX"
        } else if (Regex("""(?i)Regra\s*V\b|Quando seu Pokémon V é Nocauteado""").containsMatchIn(ruleText)) {
            s = s.replace(Regex("""(?i)\s*V$"""), "").trim()
            s = "$s V"
        } else if (Regex("""(?i)Regra.*Pokémon\s*ex\b|Quando seu Pokémon eX""").containsMatchIn(ruleText)) {
            s = s.replace(Regex("""(?i)\s*(ex|ek|eX|e)$"""), "").trim()
            s = "$s ex"
        } else if (Regex("""(?i)Regra.*Pokémon-EX\b""").containsMatchIn(ruleText)) {
            s = s.replace(Regex("""(?i)\s*EX$"""), "").trim()
            s = "$s EX"
        }

        if (s.endsWith("lI")) {
            s = s.dropLast(1) + "l"
        }

        return s
    }

    private fun isHpLine(line: OcrLine): Boolean {
        val trimmed = line.text.trim()
        if (HP_REGEX.containsMatchIn(trimmed) || HP_SOLO_REGEX.matches(trimmed)) return true
        if (line.left > 350f && (trimmed.any { it.isDigit() } || trimmed.length <= 6)) return true
        return false
    }

    // A card name is printed as text; a large purely numeric read (e.g. the HP value
    // with no "HP" prefix) must not win the name slot.
    private fun hasNameLetters(text: String): Boolean =
        text.trim().count { it.isLetter() } >= 2

    private fun isNoise(text: String): Boolean {
        val trimmed = text.trim()
        if (SLASH_NUMBER.containsMatchIn(trimmed) || PROMO.containsMatchIn(trimmed) || HP_REGEX.containsMatchIn(trimmed)) {
            return true
        }
        val upper = trimmed.uppercase()
        if (upper.contains("REGRA") || upper.startsWith("EVOLUI DE")) {
            return true
        }
        return upper in STAGE_WORDS || STAGE_PREFIX.matches(trimmed)
    }
}
