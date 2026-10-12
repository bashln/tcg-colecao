package app.tcg.app.data.sync

import androidx.room.withTransaction
import app.tcg.app.data.local.CollectionDao
import app.tcg.app.data.local.CollectionItemEntity
import app.tcg.app.data.local.SyncCursorEntity
import app.tcg.app.data.local.SyncBindingEntity
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

    /**
     * Completes a confirmed first link. The caller has already pushed local-only items
     * through `commitLink`; this writes the outcome (imported remote rows, rebased
     * revisions, the identity binding and the pull cursor) in ONE transaction, and only
     * after verifying that the local collection still matches the preview snapshot. Any
     * mismatch, unresolvable card, missing seed revision, or configuration collision
     * aborts the whole thing and leaves the local data untouched.
     */
    suspend fun completeLink(input: LinkCompletion): Boolean {
        if (input.identity.isBlank() || input.deviceId.length < 8 || input.highWater < 0) return false
        if (input.seedRevisions.values.any { it < 1 }) return false
        val seedByItem = input.seedRevisions
        return db.withTransaction {
            val existingBinding = dao.getSyncBinding()
            if (existingBinding != null && existingBinding.identity != input.identity) {
                return@withTransaction false
            }

            val currentItems = dao.listItems()
            if (!snapshotMatches(currentItems, input.expectedItems)) return@withTransaction false

            val nextItems = LinkedHashMap<String, CollectionItemEntity>()
            for (item in currentItems) nextItems[item.id] = item

            val incomingIds = mutableSetOf<String>()
            for (remote in input.remoteItems) {
                if (!incomingIds.add(remote.item.id) || remote.revision < 1) return@withTransaction false
                val local = nextItems[remote.item.id]
                if (local != null) {
                    if (!sameContent(local, remote.item)) return@withTransaction false
                    nextItems[remote.item.id] = local.copy(revision = remote.revision)
                } else {
                    val card = db.cardDao().getById(remote.item.cardId)
                    if (card == null || card.id != remote.item.cardId) return@withTransaction false
                    nextItems[remote.item.id] = remote.item.toEntity(revision = remote.revision)
                }
            }

            val seenConfigurations = mutableSetOf<String>()
            for (item in nextItems.values) {
                val key = configurationKeyOf(item)
                if (!seenConfigurations.add(key)) return@withTransaction false
                if (!incomingIds.contains(item.id) && !seedByItem.containsKey(item.id)) {
                    return@withTransaction false
                }
            }

            for (item in nextItems.values) dao.upsertRemoteItem(item)
            dao.setSyncBinding(SyncBindingEntity(slot = 0, identity = input.identity, deviceId = input.deviceId))
            advanceCursor(input.identity, input.highWater)
            true
        }
    }

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

/**
 * Everything a confirmed first link must write locally. Mirrors the web contract: the
 * expected local snapshot is re-checked at write time, remote-only rows are imported,
 * local-only rows get the revision the server assigned to the pushed operation, and the
 * binding plus pull cursor land in the same transaction.
 */
data class LinkCompletion(
    val identity: String,
    val deviceId: String,
    /** Local items exactly as they were when the preview was generated. */
    val expectedItems: List<SyncItem>,
    /** Remote rows at the preview's high-water mark, each with its server revision. */
    val remoteItems: List<RemoteLinkItem>,
    /** itemId -> server revision for local-only items pushed during commit. */
    val seedRevisions: Map<String, Long>,
    val highWater: Long,
)

private fun SyncItem.toEntity(revision: Long) = CollectionItemEntity(
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

private fun configurationKeyOf(item: CollectionItemEntity): String =
    "${item.cardId}\u001f${item.variant.name}\u001f${item.condition.name}"

/** Compares only the ownership payload, ignoring the per-side revision counter. */
private fun sameContent(local: CollectionItemEntity, remote: SyncItem): Boolean =
    local.id == remote.id &&
        local.cardId == remote.cardId &&
        local.quantity == remote.quantity &&
        local.variant == remote.variant &&
        local.condition == remote.condition &&
        local.addedAt == remote.addedAt &&
        local.pricePaidAmount == remote.pricePaid?.amount &&
        local.pricePaidCurrency == remote.pricePaid?.currency?.name

/** True when the live local rows still equal the snapshot the preview was built from. */
private fun snapshotMatches(current: List<CollectionItemEntity>, expected: List<SyncItem>): Boolean {
    if (current.size != expected.size) return false
    val byId = current.associateBy { it.id }
    return expected.all { remote ->
        val local = byId[remote.id] ?: return false
        sameContent(local, remote)
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
