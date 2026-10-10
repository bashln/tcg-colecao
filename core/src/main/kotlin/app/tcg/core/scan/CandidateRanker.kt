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
        } else if (nameMatches(name, card.name)) {
            score += 1
        }
        return score
    }

    /**
     * Whether a parsed name and a card name refer to the same card. Shared with the scan
     * candidate filter so relevance and ranking never disagree on what a name match means.
     */
    fun nameMatches(parsedName: String?, cardName: String): Boolean {
        val name = parsedName ?: return false
        return name.equals(cardName, ignoreCase = true) ||
            name.contains(cardName, ignoreCase = true) ||
            cardName.contains(name, ignoreCase = true)
    }

    // TCGdex localIds disagree on zero padding across sets ("1" vs the printed "001"), so a
    // collector-number match must compare the numeric value, not the literal string.
    private fun sameCollectorNumber(parsed: String, stored: String): Boolean =
        parsed.equals(stored, ignoreCase = true) || normalizeNumber(parsed) == normalizeNumber(stored)

    private fun normalizeNumber(value: String): String =
        value.trimStart('0').ifEmpty { "0" }
}
