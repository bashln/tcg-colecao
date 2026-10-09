package app.tcg.core.data.tcgdex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TcgdexCardCountDto(
    val official: Int? = null,
    val total: Int? = null,
)

@Serializable
data class TcgdexSetDto(
    val id: String? = null,
    val name: String? = null,
    val cardCount: TcgdexCardCountDto? = null,
)

@Serializable
data class TcgdexAttackDto(
    val name: String? = null,
    val cost: List<String>? = null,
    val damage: JsonElement? = null,
    val effect: String? = null,
)

@Serializable
data class TcgdexTcgplayerPriceDto(
    val productId: Int? = null,
    val lowPrice: Double? = null,
    val midPrice: Double? = null,
    val highPrice: Double? = null,
    val marketPrice: Double? = null,
)

@Serializable
data class TcgdexTcgplayerDto(
    val unit: String? = null,
    val updated: String? = null,
    val normal: TcgdexTcgplayerPriceDto? = null,
    val holofoil: TcgdexTcgplayerPriceDto? = null,
    @SerialName("reverse-holofoil") val reverseHolofoil: TcgdexTcgplayerPriceDto? = null,
)

@Serializable
data class TcgdexCardmarketDto(
    val updated: String? = null,
    val unit: String? = null,
    val avg: Double? = null,
    val low: Double? = null,
    val trend: Double? = null,
    val avg1: Double? = null,
    val avg7: Double? = null,
    val avg30: Double? = null,
)

/** Same shape for the top-level `pricing` and each variant's `pricing`; the mapper reads variants. */
@Serializable
data class TcgdexPricingDto(
    val cardmarket: TcgdexCardmarketDto? = null,
    val tcgplayer: TcgdexTcgplayerDto? = null,
)

@Serializable
data class TcgdexVariantDto(
    val type: String? = null,
    val size: String? = null,
    val pricing: TcgdexPricingDto? = null,
)

@Serializable
data class TcgdexCardDto(
    val id: String,
    val name: String? = null,
    val localId: String? = null,
    val image: String? = null,
    val rarity: String? = null,
    val hp: Int? = null,
    val types: List<String>? = null,
    val attacks: List<TcgdexAttackDto>? = null,
    val set: TcgdexSetDto? = null,
    @SerialName("variants_detailed") val variantsDetailed: List<TcgdexVariantDto>? = null,
    val pricing: TcgdexPricingDto? = null,
)
