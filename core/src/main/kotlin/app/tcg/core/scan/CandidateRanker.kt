package app.tcg.core.scan

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.ParsedCardText
import app.tcg.core.domain.model.Scored

object CandidateRanker {
    fun rank(parsed: ParsedCardText, candidates: List<Card>): List<Scored<Card>> =
        candidates.map { Scored(it, score(parsed, it)) }.sortedByDescending { it.score }

    private fun score(parsed: ParsedCardText, card: Card): Int {
        var score = 0
        if (parsed.collectorNumber != null && sameCollectorNumber(parsed.collectorNumber, card.collectorNumber)) {
            score += 4
        }
        if (parsed.hp != null && parsed.hp == card.hp) {
            score += 2
        }
        // Larger than the +4 number match so a matching printed set total outranks a
        // same-number card from another set when the name alone cannot separate them.
        if (parsed.setTotal != null && parsed.setTotal == card.setTotal) {
            score += 6
        }
        val name = parsed.name ?: return score
        if (name.equals(card.name, ignoreCase = true)) {
            score += 3
        } else if (name.contains(card.name, ignoreCase = true) || card.name.contains(name, ignoreCase = true)) {
            score += 1
        }
        return score
    }

    // TCGdex localIds disagree on zero padding across sets ("1" vs the printed "001"), so a
    // collector-number match must compare the numeric value, not the literal string.
    private fun sameCollectorNumber(parsed: String, stored: String): Boolean =
        parsed.equals(stored, ignoreCase = true) || normalizeNumber(parsed) == normalizeNumber(stored)

    private fun normalizeNumber(value: String): String =
        value.trimStart('0').ifEmpty { "0" }
}
