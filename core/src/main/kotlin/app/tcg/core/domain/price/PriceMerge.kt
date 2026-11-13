package app.tcg.core.domain.price

import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.PriceSource

/**
 * A market price only counts when it is a real, positive amount. Zero is how every feed
 * spells "sem preço", so it must never overwrite a price the app already knows.
 */
internal fun validMarketPrice(value: Double?): Double? = value?.takeIf { it.isFinite() && it > 0 }

object PriceMerge {
    /**
     * A refresh owns the market feeds it fetched, but a payload without pricing says nothing
     * about the card's value. Keep the last known quote for every key the payload cannot
     * price, and let keys the payload introduces through so the UI can show the variant.
     */
    fun merge(existing: List<CardPrice>, incoming: List<CardPrice>): List<CardPrice> {
        val incomingByKey = incoming.associateBy { it.key() }
        val merged = LinkedHashMap<Pair<PriceSource, CardVariant>, CardPrice>()
        for (price in existing) {
            val candidate = incomingByKey[price.key()]
            merged[price.key()] = if (candidate != null && validMarketPrice(candidate.market) != null) {
                candidate
            } else {
                price
            }
        }
        for (price in incoming) {
            if (price.key() !in merged) merged[price.key()] = price.copy(market = validMarketPrice(price.market))
        }
        return merged.values.toList()
    }

    private fun CardPrice.key(): Pair<PriceSource, CardVariant> = source to variant
}
