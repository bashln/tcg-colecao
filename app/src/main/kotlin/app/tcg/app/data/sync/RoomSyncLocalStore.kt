package app.tcg.app.data.sync

import androidx.room.withTransaction
import app.tcg.app.data.local.CollectionDao
import app.tcg.app.data.local.CollectionItemEntity
import app.tcg.app.data.local.SyncCursorEntity
import app.tcg.app.data.local.TcgDatabase
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money

/**
 * Room-backed sync store. Remote replay is authoritative (the server log only contains
 * accepted operations), and each remote apply advances the pull cursor in the SAME
 * transaction as the item write, so a crash mid-pull resumes without re-applying or
 * losing position. A cross-item configuration collision fails the whole transaction and
 * returns false instead of silently overwriting local data.
 */
class RoomSyncLocalStore(private val db: TcgDatabase) : SyncLocalStore {

    private val dao: CollectionDao
        get() = db.collectionDao()

    override suspend fun pending(identity: String): List<SyncOperation> =
        dao.listPendingOperations(identity).mapNotNull { it.toSyncOperation() }

    override suspend fun acknowledge(identity: String, opId: String): Boolean =
        dao.acknowledgeOperation(opId, identity) > 0

    override suspend fun applyRemote(identity: String, entry: SequencedOperation): Boolean =
        db.withTransaction {
            if (!writeRemote(entry.operation)) return@withTransaction false
            advanceCursor(identity, entry.sequence)
            true
        }

    override suspend fun lastSequence(identity: String): Long =
        dao.getSyncCursor(identity)?.lastSequence ?: 0L

    override suspend fun saveSequence(identity: String, sequence: Long): Boolean =
        db.withTransaction {
            advanceCursor(identity, sequence)
            true
        }

    private suspend fun advanceCursor(identity: String, sequence: Long) {
        val current = dao.getSyncCursor(identity)?.lastSequence ?: 0L
        if (sequence > current) dao.upsertSyncCursor(SyncCursorEntity(identity, sequence))
    }

    /** Returns false when the operation cannot be applied without risking silent local loss. */
    private suspend fun writeRemote(operation: SyncOperation): Boolean = when (operation) {
        is SyncOperation.Create -> {
            val owner = dao.getByKey(operation.item.cardId, operation.item.variant, operation.item.condition)
            if (owner != null && owner.id != operation.item.id) {
                false
            } else {
                dao.upsertRemoteItem(operation.item.toRemoteEntity(revision = 1))
                true
            }
        }

        is SyncOperation.Replace -> {
            val owner = dao.getByKey(operation.item.cardId, operation.item.variant, operation.item.condition)
            if (owner != null && owner.id != operation.item.id) {
                false
            } else {
                dao.upsertRemoteItem(operation.item.toRemoteEntity(revision = operation.baseRevision + 1))
                true
            }
        }

        is SyncOperation.Delete -> {
            dao.deleteById(operation.itemId)
            true
        }
    }
}

private fun SyncItem.toRemoteEntity(revision: Long) = CollectionItemEntity(
    id = id,
    cardId = cardId,
    quantity = quantity,
    condition = condition,
    variant = variant,
    pricePaidAmount = pricePaid?.amount,
    pricePaidCurrency = pricePaid?.currency?.name,
    addedAt = addedAt,
    revision = revision,
)

private fun app.tcg.app.data.local.SyncOutboxEntity.toSyncOperation(): SyncOperation? {
    val item = when {
        kind == "delete" -> null
        cardId == null || quantity == null || condition == null || variant == null -> return null
        else -> SyncItem(
            id = itemId,
            cardId = cardId,
            quantity = quantity,
            variant = enumValueOrNull<CardVariant>(variant) ?: return null,
            condition = enumValueOrNull<Condition>(condition) ?: return null,
            pricePaid = pricePaidAmount?.let { amount ->
                Money(
                    amount = amount,
                    currency = enumValueOrNull<Currency>(pricePaidCurrency ?: return null) ?: return null,
                )
            },
            addedAt = addedAt ?: 0L,
        )
    }
    return when (kind) {
        "create" -> SyncOperation.Create(opId, deviceId, checkNotNull(item))
        "replace" -> SyncOperation.Replace(opId, deviceId, baseRevision ?: return null, checkNotNull(item))
        "delete" -> SyncOperation.Delete(opId, deviceId, baseRevision ?: return null, itemId)
        else -> null
    }
}

private inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? =
    name?.let { value -> enumValues<T>().firstOrNull { it.name == value } }
