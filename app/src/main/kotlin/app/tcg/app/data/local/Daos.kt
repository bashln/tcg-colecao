package app.tcg.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.tcg.app.data.backup.ImportAction
import app.tcg.app.data.backup.ImportResult
import app.tcg.app.data.backup.ImportTicket
import app.tcg.app.data.backup.WebBackupAdapter
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.CollectionItemChanges
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.PriceSource
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import java.time.LocalDate

@Dao
abstract class CardDao {
    @Query("SELECT * FROM cards WHERE id = :id")
    abstract suspend fun getById(id: String): CardEntity?

    @Query("SELECT * FROM cards WHERE name LIKE '%' || :name || '%'")
    abstract suspend fun searchByName(name: String): List<CardEntity>

    @Query("SELECT * FROM cards WHERE collectorNumber = :collectorNumber AND (:setId IS NULL OR setId = :setId)")
    abstract suspend fun searchByNumber(collectorNumber: String, setId: String?): List<CardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(card: CardEntity)

    @Query("SELECT * FROM card_prices WHERE cardId = :cardId")
    abstract suspend fun pricesFor(cardId: String): List<CardPriceEntity>

    @Query("DELETE FROM card_prices WHERE cardId = :cardId")
    abstract suspend fun deletePricesFor(cardId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPrices(prices: List<CardPriceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPriceObservations(observations: List<CardPriceObservationEntity>)

    @Query("SELECT * FROM card_price_observations WHERE cardId = :cardId AND source = :source AND variant = :variant AND currency = :currency ORDER BY utcDay")
    abstract suspend fun priceObservationsFor(
        cardId: String,
        source: PriceSource,
        variant: CardVariant,
        currency: Currency,
    ): List<CardPriceObservationEntity>

    @Transaction
    open suspend fun cacheCard(card: Card) {
        persistCard(card, emptyList())
    }

    @Transaction
    open suspend fun captureCardPrices(card: Card, observedAt: Long) {
        val observations = card.prices.mapNotNull { it.toObservation(card.id, observedAt) }
        persistCard(card, observations)
    }

    private suspend fun persistCard(card: Card, observations: List<CardPriceObservationEntity>) {
        upsert(card.toEntity())
        deletePricesFor(card.id)
        val prices = card.prices.map { it.toEntity(card.id) }
        if (prices.isNotEmpty()) upsertPrices(prices)
        if (observations.isNotEmpty()) upsertPriceObservations(observations)
    }
}

@Dao
abstract class CollectionDao {
    @Query("""
        SELECT holdings.cardId, holdings.variant, holdings.units,
               observations.utcDay, observations.amount
        FROM (
            SELECT cardId, variant, SUM(quantity) AS units
            FROM collection_items
            GROUP BY cardId, variant
        ) AS holdings
        LEFT JOIN card_price_observations AS observations
          ON observations.cardId = holdings.cardId
         AND observations.variant = holdings.variant
         AND observations.source = :source
         AND observations.currency = :currency
         AND observations.utcDay <= :asOfUtcDay
        ORDER BY observations.utcDay, holdings.cardId, holdings.variant
    """)
    abstract fun observeCurrentHoldingsPriceRows(
        source: PriceSource,
        currency: Currency,
        asOfUtcDay: LocalDate,
    ): Flow<List<CurrentHoldingsPriceRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(item: CollectionItemEntity): Long

    @Query("UPDATE collection_items SET quantity = quantity + :quantity, revision = revision + 1 WHERE cardId = :cardId AND variant = :variant AND condition = :condition")
    abstract suspend fun addQuantity(cardId: String, variant: CardVariant, condition: Condition, quantity: Int)

    @Query("SELECT * FROM collection_items ORDER BY addedAt DESC, id")
    abstract suspend fun listItems(): List<CollectionItemEntity>

    @Transaction
    @Query("SELECT * FROM collection_items ORDER BY addedAt DESC, id")
    abstract fun observeItemsWithCard(): Flow<List<CollectionItemWithCard>>

    @Query("SELECT * FROM collection_items WHERE cardId = :cardId AND variant = :variant AND condition = :condition")
    abstract suspend fun getByKey(cardId: String, variant: CardVariant, condition: Condition): CollectionItemEntity?

    @Query("SELECT * FROM collection_items WHERE id = :itemId")
    abstract suspend fun getById(itemId: String): CollectionItemEntity?

    @Query("UPDATE collection_items SET quantity = :quantity, variant = :variant, condition = :condition, pricePaidAmount = :pricePaidAmount, pricePaidCurrency = :pricePaidCurrency, revision = revision + 1 WHERE id = :itemId")
    abstract suspend fun updateEditableFields(
        itemId: String,
        quantity: Int,
        variant: CardVariant,
        condition: Condition,
        pricePaidAmount: Double?,
        pricePaidCurrency: String?,
    ): Int

    @Query("DELETE FROM collection_items WHERE id = :itemId")
    abstract suspend fun deleteById(itemId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun setSyncBinding(binding: SyncBindingEntity)

    @Query("SELECT * FROM sync_binding WHERE slot = 0")
    abstract suspend fun getSyncBinding(): SyncBindingEntity?

    @Query("DELETE FROM sync_binding WHERE slot = 0")
    abstract suspend fun clearSyncBinding()

    @Query("SELECT * FROM sync_cursor WHERE identity = :identity")
    abstract suspend fun getSyncCursor(identity: String): SyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSyncCursor(cursor: SyncCursorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSyncConflict(conflict: SyncConflictEntity)

    @Query("SELECT * FROM sync_conflict_records WHERE identity = :identity AND resolvedAt IS NULL ORDER BY sequence")
    abstract suspend fun listSyncConflicts(identity: String): List<SyncConflictEntity>

    @Query("SELECT * FROM sync_conflict_records WHERE id = :conflictId")
    abstract suspend fun getSyncConflict(conflictId: String): SyncConflictEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertRemoteItem(item: CollectionItemEntity)

    @Insert
    abstract suspend fun insertSyncOperation(operation: SyncOutboxEntity): Long

    @Query("SELECT * FROM sync_outbox WHERE identity = :identity ORDER BY sequence")
    abstract suspend fun listPendingOperations(identity: String): List<SyncOutboxEntity>

    @Query("DELETE FROM sync_outbox WHERE opId = :opId AND identity = :identity")
    abstract suspend fun acknowledgeOperation(opId: String, identity: String): Int

    @Query("DELETE FROM sync_outbox WHERE identity = :identity AND itemId = :itemId")
    abstract suspend fun removePendingForItem(identity: String, itemId: String)

    @Transaction
    open suspend fun upsertSumming(item: CollectionItemEntity) {
        val rowId = insertIfAbsent(item)
        if (rowId == -1L) addQuantity(item.cardId, item.variant, item.condition, item.quantity)
    }

    @Transaction
    open suspend fun upsertSummingAndQueue(item: CollectionItemEntity): CollectionItemEntity {
        val existing = getByKey(item.cardId, item.variant, item.condition)
        if (existing == null) {
            val rowId = insertIfAbsent(item)
            if (rowId != -1L) {
                val created = checkNotNull(getById(item.id))
                queueCreate(created)
                return created
            }
        }

        val before = existing ?: checkNotNull(getByKey(item.cardId, item.variant, item.condition))
        addQuantity(item.cardId, item.variant, item.condition, item.quantity)
        val updated = checkNotNull(getByKey(item.cardId, item.variant, item.condition))
        queueReplace(updated, before.revision)
        return updated
    }

    @Transaction
    open suspend fun updateExact(original: CollectionItem, changes: CollectionItemChanges): CollectionWriteResult {
        val current = getById(original.id)?.toItem() ?: return CollectionWriteResult.NotFound
        val desired = original.copy(
            quantity = changes.quantity,
            variant = changes.variant,
            condition = changes.condition,
            pricePaid = changes.pricePaid,
            revision = current.revision,
        )
        if (current == desired) return CollectionWriteResult.Updated(current)
        if (current != original) return CollectionWriteResult.ChangedSinceLoaded

        val owner = getByKey(original.cardId, changes.variant, changes.condition)
        if (owner != null && owner.id != original.id) return CollectionWriteResult.DuplicateConfiguration

        val rowsUpdated = updateEditableFields(
            itemId = original.id,
            quantity = changes.quantity,
            variant = changes.variant,
            condition = changes.condition,
            pricePaidAmount = changes.pricePaid?.amount,
            pricePaidCurrency = changes.pricePaid?.currency?.name,
        )
        if (rowsUpdated != 1) return CollectionWriteResult.NotFound
        val updated = getById(original.id) ?: return CollectionWriteResult.NotFound
        queueReplace(updated, current.revision)
        return CollectionWriteResult.Updated(updated.toItem())
    }

    @Transaction
    open suspend fun removeAndQueue(itemId: String) {
        val current = getById(itemId) ?: return
        deleteById(itemId)
        val binding = getSyncBinding() ?: return
        insertSyncOperation(
            SyncOutboxEntity(
                opId = UUID.randomUUID().toString(),
                identity = binding.identity,
                deviceId = binding.deviceId,
                itemId = current.id,
                kind = "delete",
                baseRevision = current.revision,
                revision = current.revision,
                cardId = null,
                quantity = null,
                condition = null,
                variant = null,
                pricePaidAmount = null,
                pricePaidCurrency = null,
                addedAt = null,
            ),
        )
    }

    private suspend fun queueCreate(item: CollectionItemEntity) {
        val binding = getSyncBinding() ?: return
        insertSyncOperation(item.toSyncOperation(binding, "create", null))
    }

    private suspend fun queueReplace(item: CollectionItemEntity, baseRevision: Long) {
        val binding = getSyncBinding() ?: return
        insertSyncOperation(item.toSyncOperation(binding, "replace", baseRevision))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertCard(card: CardEntity)

    @Query("SELECT * FROM backup_state WHERE slot = 0")
    abstract fun observeBackupState(): Flow<BackupStateEntity?>

    @Query("SELECT * FROM backup_state WHERE slot = 0")
    abstract suspend fun getBackupState(): BackupStateEntity?

    @Query("UPDATE backup_state SET treeUri = :treeUri, failure = NULL, retentionWarning = NULL WHERE slot = 0")
    abstract suspend fun setBackupFolder(treeUri: String)

    @Query("UPDATE backup_state SET treeUri = NULL, failure = NULL, retentionWarning = NULL WHERE slot = 0")
    abstract suspend fun clearBackupFolder()

    @Query("UPDATE backup_state SET savedRevision = :savedRevision, failure = NULL, retentionWarning = :retentionWarning WHERE slot = 0 AND pendingRevision = :savedRevision")
    abstract suspend fun markBackupSaved(savedRevision: Long, retentionWarning: String?): Int

    @Query("UPDATE backup_state SET failure = :failure WHERE slot = 0")
    abstract suspend fun setBackupFailure(failure: String)

    @Query("SELECT * FROM backup_generations WHERE treeUri = :treeUri ORDER BY createdAt DESC")
    abstract suspend fun listGenerations(treeUri: String): List<BackupGenerationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertGeneration(generation: BackupGenerationEntity)

    @Query("DELETE FROM backup_generations WHERE documentUri = :documentUri")
    abstract suspend fun deleteGeneration(documentUri: String)

    @Query("SELECT * FROM backup_import_markers")
    abstract suspend fun listImportMarkers(): List<BackupImportMarkerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertImportMarker(marker: BackupImportMarkerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertImportMarkers(markers: List<BackupImportMarkerEntity>)

    @Query("SELECT * FROM cards")
    abstract suspend fun listAllCards(): List<CardEntity>

    @Query("SELECT * FROM card_prices")
    abstract suspend fun listAllPrices(): List<CardPriceEntity>

    @Transaction
    open suspend fun applyImport(ticket: ImportTicket): ImportResult {
        val currentItems = listItems()
        val currentMarkers = listImportMarkers().associate { it.itemId to it.signature }
        val currentBinding = getSyncBinding()
        val currentCardIds = listAllCards().map { it.id }.toSet()

        if (currentItems != ticket.expectedLocalState.items ||
            currentMarkers != ticket.expectedLocalState.markers ||
            currentBinding != ticket.expectedLocalState.binding
        ) {
            return ImportResult.ChangedSincePreview
        }

        val missingCards = ticket.backup.entries.map { it.card }
            .distinctBy { it.id }
            .filter { it.id !in currentCardIds }

        for (webCard in missingCards) {
            val cardEntity = CardEntity(
                id = webCard.id,
                name = webCard.name,
                setId = webCard.setId,
                setName = webCard.setName,
                collectorNumber = webCard.collectorNumber,
                rarity = null,
                hp = null,
                imageUrl = webCard.imageUrl,
                types = emptyList(),
                attacks = emptyList(),
                variants = webCard.variants.toSet(),
                setTotal = webCard.setTotal,
            )
            upsertCard(cardEntity)
        }

        for (action in ticket.actions) {
            when (action) {
                is ImportAction.Insert -> {
                    val item = CollectionItemEntity(
                        id = action.entry.item.id,
                        cardId = action.entry.item.cardId,
                        quantity = action.entry.item.quantity,
                        condition = action.entry.item.condition,
                        variant = action.entry.item.variant,
                        pricePaidAmount = action.entry.item.pricePaid?.amount,
                        pricePaidCurrency = action.entry.item.pricePaid?.currency?.name,
                        addedAt = action.entry.item.addedAt,
                        revision = action.entry.item.revision,
                    )
                    insertIfAbsent(item)
                    insertImportMarker(
                        BackupImportMarkerEntity(
                            itemId = item.id,
                            signature = WebBackupAdapter.itemSignature(action.entry.item),
                        )
                    )
                    if (currentBinding != null) {
                        insertSyncOperation(item.toSyncOperation(currentBinding, "create", null))
                    }
                }
                is ImportAction.Merge -> {
                    val local = checkNotNull(getById(action.localItemId))
                    val newQuantity = local.quantity + action.entry.item.quantity
                    updateEditableFields(
                        itemId = local.id,
                        quantity = newQuantity,
                        variant = local.variant,
                        condition = local.condition,
                        pricePaidAmount = local.pricePaidAmount,
                        pricePaidCurrency = local.pricePaidCurrency,
                    )
                    val updated = checkNotNull(getById(local.id))
                    insertImportMarker(
                        BackupImportMarkerEntity(
                            itemId = action.entry.item.id,
                            signature = WebBackupAdapter.itemSignature(action.entry.item),
                        )
                    )
                    if (currentBinding != null) {
                        insertSyncOperation(updated.toSyncOperation(currentBinding, "replace", local.revision))
                    }
                }
                is ImportAction.Unchanged -> {
                    val entry = ticket.backup.entries.first { it.item.id == action.sourceItemId }
                    insertImportMarker(
                        BackupImportMarkerEntity(
                            itemId = action.sourceItemId,
                            signature = WebBackupAdapter.itemSignature(entry.item),
                        )
                    )
                }
            }
        }
        return ImportResult.Success(ticket.summary)
    }
    @Transaction
    open suspend fun upsertNew(cardId: String, variant: CardVariant, condition: Condition, quantity: Int) {
        upsertSumming(newItem(cardId, variant, condition, quantity))
    }
}

data class CurrentHoldingsPriceRow(
    val cardId: String,
    val variant: CardVariant,
    val units: Int,
    val utcDay: LocalDate?,
    val amount: Double?,
)

sealed interface CollectionWriteResult {
    data class Updated(val item: CollectionItem) : CollectionWriteResult
    data object NotFound : CollectionWriteResult
    data object ChangedSinceLoaded : CollectionWriteResult
    data object DuplicateConfiguration : CollectionWriteResult
}

internal fun newItem(
    cardId: String,
    variant: CardVariant,
    condition: Condition,
    quantity: Int,
) = CollectionItemEntity(
    id = UUID.randomUUID().toString(),
    cardId = cardId,
    quantity = quantity,
    condition = condition,
    variant = variant,
    pricePaidAmount = null,
    pricePaidCurrency = null,
    addedAt = System.currentTimeMillis(),
)

private fun CollectionItemEntity.toSyncOperation(
    binding: SyncBindingEntity,
    kind: String,
    baseRevision: Long?,
) = SyncOutboxEntity(
    opId = UUID.randomUUID().toString(),
    identity = binding.identity,
    deviceId = binding.deviceId,
    itemId = id,
    kind = kind,
    baseRevision = baseRevision,
    revision = revision,
    cardId = cardId,
    quantity = quantity,
    condition = condition.name,
    variant = variant.name,
    pricePaidAmount = pricePaidAmount,
    pricePaidCurrency = pricePaidCurrency,
    addedAt = addedAt,
)
