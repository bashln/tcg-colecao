package app.tcg.core.domain.valuation

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource

object Valuation {
    fun itemValue(card: Card, variant: CardVariant, source: PriceSource): Money? =
        card.prices
            .firstOrNull { it.source == source && it.variant == variant && it.market != null }
            ?.let { price -> price.market?.let { Money(it, price.currency) } }

    fun portfolioTotal(entries: List<CollectionEntry>, source: PriceSource): Money {
        val total = entries.sumOf { entry ->
            (itemValue(entry.card, entry.item.variant, source)?.amount ?: 0.0) * entry.item.quantity
        }
        return Money(total, currencyOf(source))
    }

    fun currencyOf(source: PriceSource) = when (source) {
        PriceSource.TCGPLAYER -> Currency.USD
        PriceSource.CARDMARKET -> Currency.EUR
    }
}
