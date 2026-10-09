package app.tcg.core.domain.model

data class Money(val amount: Double, val currency: Currency)

data class Card(
    val id: String, val name: String, val setId: String, val setName: String,
    val collectorNumber: String, val rarity: String?, val hp: Int?,
    val types: List<String>, val attacks: List<Attack>, val imageUrl: String?,
    val variants: Set<CardVariant>, val prices: List<CardPrice>,
)

data class Attack(val name: String, val cost: List<String>, val damage: String?, val text: String?)

data class SetInfo(val id: String, val name: String, val series: String?, val releaseDate: String?)

data class CardPrice(
    val source: PriceSource, val variant: CardVariant, val currency: Currency,
    val market: Double?, val low: Double?, val mid: Double?, val high: Double?,
    val trend: Double?, val avg1: Double?, val avg7: Double?, val avg30: Double?,
    val updatedAt: String?,
)
