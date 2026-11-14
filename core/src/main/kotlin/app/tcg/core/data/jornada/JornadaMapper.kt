package app.tcg.core.data.jornada

import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.PriceSource

fun JornadaQuote.toCardPrices(variants: Set<CardVariant>): List<CardPrice> =
    variants.mapNotNull { variant ->
        val sku = byVariant[variant.name]
        // A low-liquidity card has no per-SKU lowest listing. The market ask still carries a
        // price, so the normal variant uses it instead of pretending the card has no quote.
        val market = (sku?.lowestCents ?: if (variant == CardVariant.NORMAL) marketCents else null)
            ?: return@mapNotNull null
        CardPrice(
            source = PriceSource.JORANDA,
            variant = variant,
            currency = Currency.BRL,
            market = market / 100.0,
            low = (sku?.lowestCents ?: market) / 100.0,
            mid = market / 100.0,
            high = sku?.highestCents?.let { it / 100.0 },
            trend = null,
            avg1 = null,
            avg7 = null,
            avg30 = null,
            updatedAt = fetchedAt,
            condition = sku?.condition,
            language = sku?.language,
        )
    }
