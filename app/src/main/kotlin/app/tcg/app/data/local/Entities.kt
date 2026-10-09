package app.tcg.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.tcg.core.domain.model.Attack
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.PriceSource

/** Local mirror of a resolved card so the collection works offline. */
@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey val id: String,
    val name: String,
    val setId: String,
    val setName: String,
    val collectorNumber: String,
    val rarity: String?,
    val hp: Int?,
    val imageUrl: String?,
    val types: List<String>,
    val attacks: List<Attack>,
    val variants: Set<CardVariant>,
)

@Entity(
    tableName = "collection_items",
    indices = [Index(value = ["cardId", "variant", "condition"], unique = true)],
)
data class CollectionItemEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    val quantity: Int,
    val condition: Condition,
    val variant: CardVariant,
    val pricePaidAmount: Double?,
    val pricePaidCurrency: String?,
    val addedAt: Long,
)

@Entity(tableName = "card_prices", primaryKeys = ["cardId", "source", "variant"])
data class CardPriceEntity(
    val cardId: String,
    val source: PriceSource,
    val variant: CardVariant,
    val currency: Currency,
    val market: Double?,
    val low: Double?,
    val mid: Double?,
    val high: Double?,
    val trend: Double?,
    val avg1: Double?,
    val avg7: Double?,
    val avg30: Double?,
    val updatedAt: String?,
)
