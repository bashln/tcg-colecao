package app.tcg.core.scan

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.ParsedCardText
import app.tcg.core.domain.model.Scored

object CandidateRanker {
    fun rank(parsed: ParsedCardText, candidates: List<Card>): List<Scored<Card>> =
        candidates.map { Scored(it, score(parsed, it)) }.sortedByDescending { it.score }

    private fun score(parsed: ParsedCardText, card: Card): Int {
        var score = 0
        if (parsed.collectorNumber != null && parsed.collectorNumber.equals(card.collectorNumber, ignoreCase = true)) {
            score += 4
        }
        if (parsed.hp != null && parsed.hp == card.hp) {
            score += 2
        }
        val name = parsed.name ?: return score
        if (name.equals(card.name, ignoreCase = true)) {
            score += 3
        } else if (name.contains(card.name, ignoreCase = true) || card.name.contains(name, ignoreCase = true)) {
            score += 1
        }
        return score
    }
}
