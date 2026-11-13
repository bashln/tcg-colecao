package app.tcg.core.domain.price

import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.PriceSource

fun validMarketPrice(value: Double?): Double? = value?.takeIf { it.isFinite() && it > 0 }

object PriceMerge {
    fun merge(existing: List<CardPrice>, incoming: List<CardPrice>): List<CardPrice> {
        val incomingByKey = incoming.associateBy { it.key() }
        val merged = LinkedHashMap<Pair<PriceSource, CardVariant>, CardPrice>()
        for (price in existing) {
            val candidate = incomingByKey[price.key()]
            merged[price.key()] = if (candidate != null && validMarketPrice(candidate.market) != null) {
                price.mergedWith(candidate)
            } else {
                price.copy(market = validMarketPrice(price.market))
            }
        }
        for (price in incoming) {
            if (price.key() !in merged) merged[price.key()] = price.copy(market = validMarketPrice(price.market))
        }
        return merged.values.toList()
    }

    private fun CardPrice.mergedWith(candidate: CardPrice): CardPrice = candidate.copy(
        low = candidate.low.orElse(low),
        mid = candidate.mid.orElse(mid),
        high = candidate.high.orElse(high),
        trend = candidate.trend.orElse(trend),
        avg1 = candidate.avg1.orElse(avg1),
        avg7 = candidate.avg7.orElse(avg7),
        avg30 = candidate.avg30.orElse(avg30),
        updatedAt = candidate.updatedAt ?: updatedAt,
    )

    private fun Double?.orElse(fallback: Double?): Double? = validMarketPrice(this) ?: fallback

    private fun CardPrice.key(): Pair<PriceSource, CardVariant> = source to variant
}
