package app.tcg.core.scan

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.ParsedCardText

object CandidateRanker {
    fun rank(parsed: ParsedCardText, candidates: List<Card>): List<RankedCandidate> =
        candidates.map { rank(parsed, it) }.sortedByDescending { it.score }

    private fun rank(parsed: ParsedCardText, card: Card): RankedCandidate {
        val signals = mutableSetOf<ScanSignal>()
        if (parsed.collectorNumber != null && sameCollectorNumber(parsed.collectorNumber, card.collectorNumber)) {
            signals += ScanSignal.COLLECTOR_NUMBER
        }
        if (parsed.hp != null && parsed.hp == card.hp) {
            signals += ScanSignal.HP
        }
        // A matching printed set total outranks a same-number card from another set when the
        // name alone cannot separate them.
        if (parsed.setTotal != null && parsed.setTotal == card.setTotal) {
            signals += ScanSignal.SET_TOTAL
        }
        val name = parsed.name
        if (name != null) {
            if (name.equals(card.name, ignoreCase = true)) {
                signals += ScanSignal.EXACT_NAME
            } else if (nameMatches(name, card.name)) {
                signals += ScanSignal.PARTIAL_NAME
            }
        }
        return RankedCandidate(card, signals)
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
