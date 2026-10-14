package app.tcg.app.data.local

import androidx.room.TypeConverter
import app.tcg.core.domain.model.Attack
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// Control characters cannot appear in card text, so they are safe field separators.
private const val FIELD = "\u001F"
private const val RECORD = "\u001E"
private const val SUB = "\u001D"

class Converters {
    @TypeConverter
    fun fromLocalDate(value: LocalDate): String = value.toString()

    @TypeConverter
    fun toLocalDate(value: String): LocalDate = LocalDate.parse(value)

    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(FIELD)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(FIELD)

    @TypeConverter
    fun fromVariantSet(value: Set<CardVariant>): String = value.joinToString(FIELD) { it.name }

    @TypeConverter
    fun toVariantSet(value: String): Set<CardVariant> =
        if (value.isEmpty()) emptySet() else value.split(FIELD).map(CardVariant::valueOf).toSet()

    @TypeConverter
    fun fromAttackList(value: List<Attack>): String = value.joinToString(RECORD) { attack ->
        listOf(
            attack.name,
            attack.cost.joinToString(SUB),
            attack.damage.orEmpty(),
            attack.text.orEmpty(),
        ).joinToString(FIELD)
    }

    @TypeConverter
    fun toAttackList(value: String): List<Attack> =
        if (value.isEmpty()) emptyList() else value.split(RECORD).map { record ->
            val parts = record.split(FIELD)
            Attack(
                name = parts.getOrElse(0) { "" },
                cost = parts.getOrElse(1) { "" }.let { if (it.isEmpty()) emptyList() else it.split(SUB) },
                damage = parts.getOrNull(2)?.takeIf(String::isNotEmpty),
                text = parts.getOrNull(3)?.takeIf(String::isNotEmpty),
            )
        }
}

fun Card.toEntity() = CardEntity(
    id = id,
    name = name,
    setId = setId,
    setName = setName,
    collectorNumber = collectorNumber,
    rarity = rarity,
    hp = hp,
    imageUrl = imageUrl,
    types = types,
    attacks = attacks,
    variants = variants,
    setTotal = setTotal,
)

fun CardEntity.toCard(prices: List<CardPriceEntity>): Card = Card(
    id = id,
    name = name,
    setId = setId,
    setName = setName,
    collectorNumber = collectorNumber,
    rarity = rarity,
    hp = hp,
    imageUrl = imageUrl,
    types = types,
    attacks = attacks,
    variants = variants,
    prices = prices.map(CardPriceEntity::toPrice),
    setTotal = setTotal,
)

fun CardPrice.toEntity(cardId: String) = CardPriceEntity(
    cardId = cardId,
    source = source,
    variant = variant,
    currency = currency,
    market = market,
    low = low,
    mid = mid,
    high = high,
    trend = trend,
    avg1 = avg1,
    avg7 = avg7,
    avg30 = avg30,
    updatedAt = updatedAt,
)

fun CardPrice.toObservation(cardId: String, observedAt: Long): CardPriceObservationEntity? =
    market?.let { amount ->
        CardPriceObservationEntity(
            cardId = cardId,
            source = source,
            variant = variant,
            currency = currency,
            amount = amount,
            observedAt = observedAt,
            utcDay = Instant.ofEpochMilli(observedAt).atZone(ZoneOffset.UTC).toLocalDate(),
        )
    }

fun CardPriceEntity.toPrice() = CardPrice(
    source = source,
    variant = variant,
    currency = currency,
    market = market,
    low = low,
    mid = mid,
    high = high,
    trend = trend,
    avg1 = avg1,
    avg7 = avg7,
    avg30 = avg30,
    updatedAt = updatedAt,
)

fun CollectionItemEntity.toItem() = CollectionItem(
    id = id,
    cardId = cardId,
    quantity = quantity,
    condition = condition,
    variant = variant,
    pricePaid = pricePaidAmount?.let { Money(it, pricePaidCurrency.toCurrency()) },
    addedAt = addedAt,
    revision = revision,
)

private fun String?.toCurrency(): Currency = Currency.valueOf(this ?: Currency.USD.name)
