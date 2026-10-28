package app.tcg.core.domain.stats

import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.valuation.Valuation

/**
 * One bucket of a breakdown: how many cards fall in it and what they are worth.
 * [key] is the raw domain value; the UI resolves it to a localized label.
 */
data class StatBucket<K>(
    val key: K,
    val value: Money,
    val count: Int,
)

data class InvestmentSummary(
    val investedByCurrency: List<Money>,
    val profit: ProfitResult,
)

sealed interface ProfitResult {
    data class Available(val amount: Money) : ProfitResult
    data class Unavailable(val reason: ProfitUnavailableReason) : ProfitResult
}

enum class ProfitUnavailableReason {
    INCOMPLETE_PURCHASE_RECORDS,
    MIXED_PURCHASE_CURRENCIES,
    PURCHASE_CURRENCY_DIFFERS_FROM_MARKET,
    MISSING_MARKET_QUOTES,
}

/**
 * Aggregates of the whole collection at the currently selected [PriceSource]. Pure so the
 * same numbers drive Home, Profile and any future surface, and so they test on the JVM.
 */
data class CollectionStats(
    val totalValue: Money,
    val totalCards: Int,
    val unpricedCards: Int,
    val distinctCards: Int,
    val distinctSets: Int,
    val byCondition: List<StatBucket<Condition>>,
    val byVariant: List<StatBucket<CardVariant>>,
    val byRarity: List<StatBucket<String?>>,
    val mostValuable: List<CollectionEntry>,
    val investment: InvestmentSummary?,
) {
    companion object {
        /** Top of the "most valuable" ranking shown on Home. */
        const val MOST_VALUABLE_LIMIT = 5

        val EMPTY = CollectionStats(
            totalValue = Money(0.0, Currency.USD),
            totalCards = 0,
            unpricedCards = 0,
            distinctCards = 0,
            distinctSets = 0,
            byCondition = emptyList(),
            byVariant = emptyList(),
            byRarity = emptyList(),
            mostValuable = emptyList(),
            investment = null,
        )

        fun compute(entries: List<CollectionEntry>, source: PriceSource): CollectionStats {
            val totalValue = Valuation.portfolioTotal(entries, source)
            val paid = entries.mapNotNull { it.item.pricePaid }
            val investedByCurrency = paid.groupBy { it.currency }
                .toSortedMap(compareBy { it.name })
                .map { (currency, amounts) -> Money(amounts.sumOf { it.amount }, currency) }
            val investment = if (paid.isEmpty()) null else InvestmentSummary(
                investedByCurrency = investedByCurrency,
                profit = when {
                    paid.size != entries.size -> ProfitResult.Unavailable(
                        ProfitUnavailableReason.INCOMPLETE_PURCHASE_RECORDS,
                    )
                    investedByCurrency.size > 1 -> ProfitResult.Unavailable(
                        ProfitUnavailableReason.MIXED_PURCHASE_CURRENCIES,
                    )
                    investedByCurrency.single().currency != Valuation.currencyOf(source) -> ProfitResult.Unavailable(
                        ProfitUnavailableReason.PURCHASE_CURRENCY_DIFFERS_FROM_MARKET,
                    )
                    entries.any { Valuation.itemValue(it.card, it.item.variant, source) == null } ->
                        ProfitResult.Unavailable(ProfitUnavailableReason.MISSING_MARKET_QUOTES)
                    else -> ProfitResult.Available(
                        Money(totalValue.amount - investedByCurrency.single().amount, totalValue.currency),
                    )
                },
            )

            return CollectionStats(
                totalValue = totalValue,
                totalCards = entries.sumOf { it.item.quantity },
                unpricedCards = entries.filter {
                    Valuation.itemValue(it.card, it.item.variant, source) == null
                }.sumOf { it.item.quantity },
                distinctCards = entries.map { it.card.id }.distinct().size,
                distinctSets = entries.map { it.card.setId }.distinct().size,
                byCondition = entries.bucketed(source) { it.item.condition },
                byVariant = entries.bucketed(source) { it.item.variant },
                byRarity = entries.bucketed(source) { it.card.rarity },
                mostValuable = entries
                    .sortedWith(
                        compareByDescending<CollectionEntry> { it.lineValue(source) }
                            .thenBy { it.card.name }
                            .thenBy { it.item.id },
                    )
                    .take(MOST_VALUABLE_LIMIT),
                investment = investment,
            )
        }

        /** Groups by [key] and orders buckets by card count, then value, so the UI is stable. */
        private fun <K> List<CollectionEntry>.bucketed(
            source: PriceSource,
            key: (CollectionEntry) -> K,
        ): List<StatBucket<K>> = groupBy(key)
            .map { (bucketKey, group) ->
                StatBucket(
                    key = bucketKey,
                    value = Money(group.sumOf { it.lineValue(source) }, Valuation.currencyOf(source)),
                    count = group.sumOf { it.item.quantity },
                )
            }
            .sortedWith(compareByDescending<StatBucket<K>> { it.count }.thenByDescending { it.value.amount })

        private fun CollectionEntry.lineValue(source: PriceSource): Double =
            (Valuation.itemValue(card, item.variant, source)?.amount ?: 0.0) * item.quantity
    }
}
