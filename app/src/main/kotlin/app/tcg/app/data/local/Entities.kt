package app.tcg.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Relation
import app.tcg.core.domain.model.Attack
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.PriceSource
import java.time.LocalDate

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
    val setTotal: Int?,
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
    @ColumnInfo(defaultValue = "1") val revision: Long = 1,
)

@Entity(tableName = "sync_binding")
data class SyncBindingEntity(
    @PrimaryKey val slot: Int = 0,
    val identity: String,
    val deviceId: String,
)

/** Per-identity pull cursor. Advances monotonically so a crash resumes without re-applying. */
@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val identity: String,
    val lastSequence: Long,
)

@Entity(tableName = "sync_conflict_records", indices = [Index(value = ["identity"])])
data class SyncConflictEntity(
    @PrimaryKey val id: String,
    val identity: String,
    val sequence: Long,
    val itemId: String,
    val reason: String,
    val localPayload: String?,
    val remotePayload: String,
    val resolution: String? = null,
    val resolutionOperationId: String? = null,
    val resolutionOperation: String? = null,
    val resolvedAt: Long? = null,
    @ColumnInfo(defaultValue = "'pull'") val source: String = "pull",
    val serverConflictId: String? = null,
    val opId: String? = null,
    val rejectedOperation: String? = null,
    val cloudItem: String? = null,
    val conflictingItem: String? = null,
    val observedHighWater: Long? = null,
    val resolutionId: String? = null,
    val resolutionChoice: String? = null,
    val resolutionResult: String? = null,
    @ColumnInfo(defaultValue = "0") val resolutionApplied: Boolean = false,
)

@Entity(
    tableName = "sync_outbox",
    indices = [Index(value = ["opId"], unique = true), Index(value = ["identity", "sequence"])],
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val opId: String,
    val identity: String,
    val deviceId: String,
    val itemId: String,
    val kind: String,
    val baseRevision: Long?,
    val revision: Long,
    val cardId: String?,
    val quantity: Int?,
    val condition: String?,
    val variant: String?,
    val pricePaidAmount: Double?,
    val pricePaidCurrency: String?,
    val addedAt: Long?,
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

@Entity(
    tableName = "card_price_observations",
    primaryKeys = ["cardId", "source", "variant", "currency", "utcDay"],
)
data class CardPriceObservationEntity(
    val cardId: String,
    val source: PriceSource,
    val variant: CardVariant,
    val currency: Currency,
    val amount: Double,
    val observedAt: Long,
    val utcDay: LocalDate,
)

/** Reactive row: a collection item joined to its mirrored card. */
data class CollectionItemWithCard(
    @Embedded val item: CollectionItemEntity,
    @Relation(parentColumn = "cardId", entityColumn = "id") val card: CardEntity,
)
