package app.tcg.app.data.prices

import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.data.repository.PriceRefreshReport

class PriceRefresher(
    private val collection: CollectionRepository,
    private val cards: CardRepository,
) {

    suspend fun refresh(): PriceRefreshReport {
        val cardIds = collection.list().map { it.card.id }.distinct()
        if (cardIds.isEmpty()) return PriceRefreshReport(requested = 0, refreshed = 0, unavailable = 0)
        return cards.refreshPrices(cardIds)
    }
}
