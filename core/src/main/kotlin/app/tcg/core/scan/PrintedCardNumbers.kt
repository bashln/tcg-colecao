package app.tcg.core.scan

import app.tcg.core.domain.model.Card

data class PrintedCardNumber(val localId: String, val setTotal: Int) {
    init {
        require(localId.isNotBlank() && setTotal > 0)
    }
}

data class PrintedCardNumberSearchResult(
    val cards: List<Card>,
    val unmatchedReferences: List<String>,
)

fun parsePrintedCardNumbers(input: String): List<PrintedCardNumber>? {
    val tokens = input.split(',', ';', '\n', '\r').map(String::trim).filter(String::isNotEmpty)
    if (tokens.isEmpty()) return null

    val references = tokens.map { token ->
        val match = PRINTED_CARD_NUMBER.matchEntire(token) ?: return null
        val setTotal = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        PrintedCardNumber(match.groupValues[1], setTotal)
    }
    return references.distinct()
}

fun matchPrintedCardNumbers(
    references: List<PrintedCardNumber>,
    candidates: List<Card>,
): PrintedCardNumberSearchResult {
    val matched = linkedMapOf<String, Card>()
    val unmatched = mutableListOf<String>()
    references.forEach { reference ->
        val matches = candidates.filter { card ->
            card.collectorNumber.normalizedLocalId() == reference.localId.normalizedLocalId() &&
                card.setTotal == reference.setTotal
        }
        if (matches.isEmpty()) {
            unmatched += reference.displayValue()
        } else {
            matches.forEach { card -> matched.putIfAbsent(card.id, card) }
        }
    }
    return PrintedCardNumberSearchResult(matched.values.toList(), unmatched)
}

private fun String.normalizedLocalId(): String = toIntOrNull()?.toString() ?: lowercase()

private fun PrintedCardNumber.displayValue(): String = "$localId/${setTotal.toString().padStart(3, '0')}"

private val PRINTED_CARD_NUMBER = Regex("([A-Za-z0-9]+)/([0-9]+)")
