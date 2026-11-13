package app.tcg.core.data.jornada

import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.PriceSource

fun JornadaQuote.toCardPrices(variants: Set<CardVariant>): List<CardPrice> =
    variants.mapNotNull { variant ->
        val sku = byVariant[variant.name] ?: return@mapNotNull null
        val lowest = sku.lowestCents ?: return@mapNotNull null
        CardPrice(
            source = PriceSource.JORANDA,
            variant = variant,
            currency = Currency.BRL,
            market = lowest / 100.0,
            low = lowest / 100.0,
            mid = lowest / 100.0,
            high = sku.highestCents?.let { it / 100.0 },
            trend = null,
            avg1 = null,
            avg7 = null,
            avg30 = null,
            updatedAt = fetchedAt,
            condition = sku.condition,
            language = sku.language,
        )
    }
