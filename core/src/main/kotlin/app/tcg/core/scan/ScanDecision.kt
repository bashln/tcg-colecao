package app.tcg.core.scan

import app.tcg.core.domain.model.Card

/** What a candidate actually matched in the scan. The score sums these; the decision names them. */
enum class ScanSignal { EXACT_NAME, PARTIAL_NAME, COLLECTOR_NUMBER, SET_TOTAL, HP }

data class RankedCandidate(val card: Card, val score: Int, val signals: Set<ScanSignal>)

sealed interface ScanDecision {
    data class Confirmed(val candidates: List<RankedCandidate>) : ScanDecision
    data object TooWeak : ScanDecision

    companion object {
        /**
         * An exact name identifies the card. A lone collector number does too, but only when no
         * other candidate carries it, since every set numbers its own cards from 1. Set total and
         * HP only order the list, so on their own they never confirm.
         */
        fun from(ranked: List<RankedCandidate>): ScanDecision {
            val top = ranked.firstOrNull() ?: return TooWeak
            if (ScanSignal.EXACT_NAME in top.signals) return Confirmed(ranked)
            val numberMatches = ranked.count { ScanSignal.COLLECTOR_NUMBER in it.signals }
            if (ScanSignal.COLLECTOR_NUMBER in top.signals && numberMatches == 1) {
                return Confirmed(ranked)
            }
            return TooWeak
        }
    }
}
