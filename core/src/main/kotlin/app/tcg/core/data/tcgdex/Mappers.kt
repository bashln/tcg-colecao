package app.tcg.core.data.tcgdex

import app.tcg.core.domain.model.Attack
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.price.validMarketPrice
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

private const val IMAGE_SUFFIX = "/high.webp"

internal fun TcgdexCardDto.toCard(): Card = Card(
    id = id,
    name = name.orEmpty(),
    setId = set?.id ?: id.substringBeforeLast('-'),
    setName = set?.name.orEmpty(),
    collectorNumber = localId.orEmpty(),
    rarity = rarity,
    hp = hp,
    types = types.orEmpty(),
    attacks = attacks.orEmpty().map { it.toAttack() },
    imageUrl = image?.takeIf { it.isNotBlank() }?.let { it + IMAGE_SUFFIX } ?: tcgplayerProductImage(),
    variants = variantsDetailed.orEmpty().mapNotNull { variantFromType(it.type) }.toSet(),
    prices = prices(),
    // The printed denominator ("NNN/TTT") is TCGdex's `official` count; `total` also counts
    // unnumbered secret rares and is never what the card shows.
    setTotal = set?.cardCount?.official,
)

/**
 * TCGdex omits `image` for a few printings (the whole `mep` promo set, for one), but its
 * pricing block still carries the TCGplayer product id and that CDN serves the same artwork.
 */
private fun TcgdexCardDto.tcgplayerProductImage(): String? {
    val productId = variantsDetailed.orEmpty().firstNotNullOfOrNull { variant ->
        variant.pricing?.tcgplayer?.let { tcgplayer ->
            tcgplayer.holofoil?.productId
                ?: tcgplayer.normal?.productId
                ?: tcgplayer.reverseHolofoil?.productId
        }
    }
    return productId?.let { "https://tcgplayer-cdn.tcgplayer.com/product/${it}_400w.jpg" }
}

private fun TcgdexCardDto.prices(): List<CardPrice> =
    variantsDetailed.orEmpty().flatMap { variant ->
        val type = variantFromType(variant.type) ?: return@flatMap emptyList()
        buildList {
            variant.pricing?.cardmarket?.let { add(it.toCardPrice(type)) }
            val tcgplayer = variant.pricing?.tcgplayer
            tcgplayer?.priceFor(type)?.let { add(it.toCardPrice(type, tcgplayer.updated)) }
        }
    }

internal fun variantFromType(type: String?): CardVariant? = when (type?.trim()?.lowercase()) {
    "normal" -> CardVariant.NORMAL
    "holo", "holofoil" -> CardVariant.HOLOFOIL
    "reverse", "reverse-holofoil" -> CardVariant.REVERSE_HOLOFOIL
    "firstedition", "first-edition" -> CardVariant.FIRST_EDITION
    "promo" -> CardVariant.PROMO
    else -> null
}

private fun TcgdexAttackDto.toAttack() = Attack(name.orEmpty(), cost.orEmpty(), damage.asStringOrNull(), effect)

private fun JsonElement?.asStringOrNull(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}

private fun TcgdexCardmarketDto.toCardPrice(variant: CardVariant) = CardPrice(
    source = PriceSource.CARDMARKET,
    variant = variant,
    currency = Currency.EUR,
    market = price(avg, avgHolo, variant),
    low = price(low, lowHolo, variant),
    mid = null,
    high = null,
    trend = price(trend, trendHolo, variant),
    avg1 = price(avg1, avg1Holo, variant),
    avg7 = price(avg7, avg7Holo, variant),
    avg30 = price(avg30, avg30Holo, variant),
    updatedAt = updated,
)

private fun price(base: Double?, holo: Double?, variant: CardVariant): Double? =
    if (variant == CardVariant.HOLOFOIL) validMarketPrice(holo) ?: validMarketPrice(base) else validMarketPrice(base)

private fun TcgdexTcgplayerPriceDto.toCardPrice(variant: CardVariant, updatedAt: String?) = CardPrice(
    source = PriceSource.TCGPLAYER,
    variant = variant,
    currency = Currency.USD,
    market = validMarketPrice(marketPrice),
    low = validMarketPrice(lowPrice),
    mid = validMarketPrice(midPrice),
    high = validMarketPrice(highPrice),
    trend = null,
    avg1 = null,
    avg7 = null,
    avg30 = null,
    updatedAt = updatedAt,
)

private fun TcgdexTcgplayerDto.priceFor(variant: CardVariant): TcgdexTcgplayerPriceDto? = when (variant) {
    CardVariant.NORMAL -> normal
    CardVariant.HOLOFOIL -> holofoil
    CardVariant.REVERSE_HOLOFOIL -> reverseHolofoil
    CardVariant.FIRST_EDITION, CardVariant.PROMO -> null
}
