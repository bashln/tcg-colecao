package app.tcg.core.scan

import app.tcg.core.domain.model.Card

enum class ScanSignal(val weight: Int) {
    EXACT_NAME(7),
    SET_TOTAL(6),
    COLLECTOR_NUMBER(4),
    HP(2),
    PARTIAL_NAME(1),
}

data class RankedCandidate(val card: Card, val signals: Set<ScanSignal>) {
    val score: Int get() = signals.sumOf { it.weight }
}

sealed interface ScanDecision {
    data class Confirmed(val candidates: List<RankedCandidate>) : ScanDecision
    data object TooWeak : ScanDecision

    companion object {
        /** The printed "NN/TTT" pair: the number plus the set size it was printed against. */
        private val PRINTED_PAIR = setOf(ScanSignal.COLLECTOR_NUMBER, ScanSignal.SET_TOTAL)

        /**
         * An exact name identifies the card, even when set total and HP outrank it in the list,
         * so a unique exact name moves to the top. A lone collector number identifies the card
         * only when no other candidate carries it, since every set numbers its own cards from 1.
         * Because of that, the number alone rarely separates anything in the real catalog, so the
         * printed total is what identifies the card: when exactly one candidate matches both
         * halves of "NN/TTT", that pair names the card. A pair shared by two sets is still weak.
         * Set total and HP on their own only order the list, so they never confirm.
         */
        fun from(ranked: List<RankedCandidate>): ScanDecision {
            if (ranked.isEmpty()) return TooWeak
            val exactNames = ranked.filter { ScanSignal.EXACT_NAME in it.signals }
            if (exactNames.size == 1) {
                return Confirmed(listOf(exactNames.single()) + ranked - exactNames.single())
            }
            if (exactNames.size > 1) return Confirmed(ranked)
            val top = ranked.first()
            val numberMatches = ranked.count { ScanSignal.COLLECTOR_NUMBER in it.signals }
            if (ScanSignal.COLLECTOR_NUMBER in top.signals) {
                if (numberMatches == 1) return Confirmed(ranked)
                val pairMatches = ranked.count { it.signals.containsAll(PRINTED_PAIR) }
                if (ScanSignal.SET_TOTAL in top.signals && pairMatches == 1) return Confirmed(ranked)
            }
            return TooWeak
        }
    }
}
