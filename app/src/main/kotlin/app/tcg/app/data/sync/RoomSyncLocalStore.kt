package app.tcg.app.data.sync

import androidx.room.withTransaction
import app.tcg.app.data.local.CollectionDao
import app.tcg.app.data.local.CollectionItemEntity
import app.tcg.app.data.local.SyncCursorEntity
import app.tcg.app.data.local.SyncBindingEntity
import app.tcg.app.data.local.SyncConflictEntity
import app.tcg.app.data.local.SyncOutboxEntity
import app.tcg.app.data.local.TcgDatabase
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID

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
        db.withTransaction {
            val blocked = dao.listSyncConflicts(identity).filter { it.source == "push" }.mapNotNull { it.opId }.toSet()
            dao.listPendingOperations(identity).filterNot { it.opId in blocked }.mapNotNull { it.toSyncOperation() }
        }

    override suspend fun deviceId(identity: String): String? =
        dao.getSyncBinding()?.takeIf { it.identity == identity }?.deviceId

    override suspend fun acknowledge(identity: String, opId: String): Boolean =
        dao.acknowledgeOperation(opId, identity) > 0

    override suspend fun applyRemote(identity: String, entry: SequencedOperation): RemoteApplyResult =
        db.withTransaction {
            val applied = writeRemote(entry.operation)
            if (applied is RemoteWrite.Conflict) {
                val detail = SyncConflictDetail(
                    id = "$identity:${entry.sequence}", identity = identity, sequence = entry.sequence,
                    itemId = entry.operation.itemId(), reason = applied.reason,
                    local = applied.local?.toSyncItem(), remote = entry.operation,
                )
                dao.upsertSyncConflict(detail.toEntity())
                return@withTransaction RemoteApplyResult.Conflict(detail)
            }
            advanceCursor(identity, entry.sequence)
            RemoteApplyResult.Applied
        }

    override suspend fun conflicts(identity: String): List<SyncConflictDetail> =
        dao.listSyncConflicts(identity).mapNotNull(SyncConflictEntity::toDetail)

    override suspend fun savePushConflict(identity: String, conflict: PushConflict): Boolean = db.withTransaction {
        val binding = dao.getSyncBinding() ?: return@withTransaction false
        if (binding.identity != identity) return@withTransaction false
        val current = dao.getSyncConflict(conflict.id)?.takeIf { it.identity == identity && it.source == "push" }
        val serverReceipt = conflict.resolution
        val local = dao.getById(conflict.itemId)?.toSyncItem()
        val rejected = conflict.rejectedOperation
        dao.upsertSyncConflict(
            SyncConflictEntity(
                id = conflict.id,
                identity = identity,
                sequence = 0L,
                itemId = conflict.itemId,
                reason = conflict.reason,
                localPayload = local?.let(::encodeSyncItem) ?: current?.localPayload,
                remotePayload = rejected?.let(::encodeSyncOperation) ?: current?.remotePayload ?: "{}",
                resolvedAt = current?.resolvedAt,
                source = "push",
                serverConflictId = conflict.id,
                opId = conflict.opId,
                rejectedOperation = rejected?.let(::encodeSyncOperation),
                cloudItem = conflict.cloudItem?.let(::encodePushCloudItem),
                conflictingItem = conflict.conflictingItem?.let(::encodePushCloudItem),
                observedHighWater = conflict.highWater,
                resolutionId = serverReceipt?.id ?: current?.resolutionId,
                resolutionChoice = serverReceipt?.choice?.wireValue ?: current?.resolutionChoice,
                resolutionResult = serverReceipt?.result?.toString() ?: current?.resolutionResult,
                resolutionApplied = current?.resolutionApplied ?: false,
            ),
        )
        true
    }

    override suspend fun applyPushConflictResolution(identity: String, conflictId: String, receipt: PushResolutionResponse): Boolean = db.withTransaction {
        val binding = dao.getSyncBinding() ?: return@withTransaction false
        if (binding.identity != identity || receipt.conflictId != conflictId || receipt.status !in setOf("resolved", "already-resolved")) return@withTransaction false
        val resolutionId = receipt.resolutionId ?: return@withTransaction false
        val choice = receipt.choice ?: return@withTransaction false
        val result = receipt.result?.let(::JsonObject) ?: return@withTransaction false
        val row = dao.getSyncConflict(conflictId) ?: return@withTransaction false
        if (row.identity != identity || row.source != "push" || row.serverConflictId != conflictId) return@withTransaction false
        if (row.resolutionApplied && row.resolutionId == resolutionId) return@withTransaction true
        val conflict = row.toDetail()?.pushConflict ?: return@withTransaction false
        val rejected = conflict.rejectedOperation
        val itemIds = buildSet {
            add(conflict.itemId)
            result.string("itemId")?.let(::add)
            result.string("localItemId")?.let(::add)
            result.string("conflictingItemId")?.let(::add)
        }

        when (choice) {
            PushConflictChoice.KEEP_CLOUD -> {
                val remoteStates = listOf(
                    decodePushCloudItem(result["cloudItem"]),
                    decodePushCloudItem(result["conflictingItem"]),
                )
                val present = remoteStates.filterIsInstance<PushCloudItem.Present>()
                for (remote in present) {
                    val card = db.cardDao().getById(remote.item.cardId) ?: return@withTransaction false
                    if (card.id != remote.item.cardId) return@withTransaction false
                }
                itemIds.filter { id -> present.none { it.item.id == id } }.forEach { dao.deleteById(it) }
                present.forEach { dao.upsertRemoteItem(it.item.toRemoteEntity(it.revision)) }
                itemIds.forEach { dao.removePendingForItem(identity, it) }
            }
            PushConflictChoice.KEEP_LOCAL -> {
                if (rejected is SyncOperation.Delete) {
                    itemIds.forEach { dao.deleteById(it) }
                    rejected.let { dao.acknowledgeOperation(it.opId, identity) }
                    dao.upsertSyncConflict(row.copy(
                        resolutionId = resolutionId,
                        resolutionChoice = choice.wireValue,
                        resolutionResult = result.toString(),
                        resolutionApplied = true,
                    ))
                    return@withTransaction true
                }
                val source = row.localPayload?.let { runCatching { decodeSyncItem(it) }.getOrNull() }
                    ?: rejected?.toLocalItem()
                    ?: return@withTransaction false
                val targetId = result.string("itemId") ?: return@withTransaction false
                val revision = result.long("revision")?.takeIf { it > 0 } ?: return@withTransaction false
                val rejectedOpId = rejected?.opId ?: return@withTransaction false
                val chosen = source.copy(id = targetId)
                itemIds.filter { it != targetId }.forEach { dao.deleteById(it) }
                dao.upsertRemoteItem(chosen.toRemoteEntity(revision))
                dao.acknowledgeOperation(rejectedOpId, identity)
            }
        }
        dao.upsertSyncConflict(
            row.copy(
                resolutionId = resolutionId,
                resolutionChoice = choice.wireValue,
                resolutionResult = result.toString(),
                resolutionApplied = true,
            ),
        )
        true
    }

    override suspend fun finishPushConflictResolution(identity: String, conflictId: String, resolutionId: String): Boolean = db.withTransaction {
        val binding = dao.getSyncBinding() ?: return@withTransaction false
        if (binding.identity != identity) return@withTransaction false
        val row = dao.getSyncConflict(conflictId) ?: return@withTransaction false
        if (row.identity != identity || row.source != "push" || row.resolutionId != resolutionId || !row.resolutionApplied) return@withTransaction false
        if (row.resolvedAt == null) dao.upsertSyncConflict(row.copy(resolvedAt = System.currentTimeMillis()))
        true
    }

    override suspend fun resolveConflict(identity: String, conflictId: String, resolution: SyncConflictResolution): SyncOperation? =
        db.withTransaction {
            val binding = dao.getSyncBinding() ?: return@withTransaction null
            if (binding.identity != identity) return@withTransaction null
            val row = dao.getSyncConflict(conflictId) ?: return@withTransaction null
            if (row.identity != identity || row.resolvedAt != null) return@withTransaction null
            if (row.resolutionOperation != null) return@withTransaction decodeSyncOperation(row.resolutionOperation)
            val detail = row.toDetail() ?: return@withTransaction null
            val remote = detail.remote ?: return@withTransaction null
            if (resolution == SyncConflictResolution.KeepCloud) {
                val localId = detail.local?.id
                if (localId != null) dao.removePendingForItem(identity, localId)
                dao.removePendingForItem(identity, remote.itemId())
                if (localId != null && localId != remote.itemId()) dao.deleteById(localId)
                when (remote) {
                    is SyncOperation.Create -> dao.upsertRemoteItem(remote.item.toRemoteEntity(1))
                    is SyncOperation.Replace -> dao.upsertRemoteItem(remote.item.toRemoteEntity(remote.baseRevision + 1))
                    is SyncOperation.Delete -> dao.deleteById(remote.itemId)
                }
                dao.upsertSyncConflict(row.copy(resolution = resolution.name, resolvedAt = System.currentTimeMillis()))
                advanceCursor(identity, row.sequence)
                return@withTransaction null
            }

            val local = detail.local ?: return@withTransaction null
            val operation: SyncOperation
            val entity: CollectionItemEntity
            when (remote) {
                is SyncOperation.Create -> {
                    operation = SyncOperation.Replace(UUID.randomUUID().toString(), binding.deviceId, 1L, local.copy(id = remote.item.id))
                    entity = local.toRemoteEntity(2L)
                }
                is SyncOperation.Replace -> {
                    val revision = remote.baseRevision + 1
                    operation = SyncOperation.Replace(UUID.randomUUID().toString(), binding.deviceId, revision, local.copy(id = remote.item.id))
                    entity = local.toRemoteEntity(revision + 1)
                }
                is SyncOperation.Delete -> {
                    val newId = UUID.randomUUID().toString()
                    operation = SyncOperation.Create(UUID.randomUUID().toString(), binding.deviceId, local.copy(id = newId))
                    entity = local.toRemoteEntity(1L).copy(id = newId)
                }
            }
            if (entity.id != local.id) dao.deleteById(local.id)
            dao.removePendingForItem(identity, local.id)
            dao.removePendingForItem(identity, entity.id)
            dao.upsertRemoteItem(entity)
            dao.insertSyncOperation(operation.toOutbox(identity))
            dao.upsertSyncConflict(row.copy(
                resolution = resolution.name,
                resolutionOperationId = operation.opId,
                resolutionOperation = encodeSyncOperation(operation),
            ))
            operation
        }

    override suspend fun finishConflict(identity: String, conflictId: String, operationId: String): Boolean =
        db.withTransaction {
            val binding = dao.getSyncBinding() ?: return@withTransaction false
            if (binding.identity != identity) return@withTransaction false
            val row = dao.getSyncConflict(conflictId) ?: return@withTransaction false
            if (row.identity != identity || row.resolutionOperationId != operationId) return@withTransaction false
            if (row.resolvedAt != null) return@withTransaction true
            dao.upsertSyncConflict(row.copy(resolvedAt = System.currentTimeMillis()))
            advanceCursor(identity, row.sequence)
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

    private sealed interface RemoteWrite {
        data object Applied : RemoteWrite
        data class Conflict(val reason: String, val local: CollectionItemEntity?) : RemoteWrite
    }

    private suspend fun writeRemote(operation: SyncOperation): RemoteWrite {
        return when (operation) {
        is SyncOperation.Create -> {
            val existing = dao.getById(operation.item.id)
            if (existing != null && !sameContent(existing, operation.item)) {
                RemoteWrite.Conflict("id-collision", existing)
            } else {
                val owner = dao.getByKey(operation.item.cardId, operation.item.variant, operation.item.condition)
                if (owner != null && owner.id != operation.item.id) {
                    RemoteWrite.Conflict("duplicate-configuration", owner)
                } else {
                    dao.upsertRemoteItem(operation.item.toRemoteEntity(revision = 1))
                    RemoteWrite.Applied
                }
            }
        }

        is SyncOperation.Replace -> {
            val current = dao.getById(operation.item.id)
            if (current == null) return RemoteWrite.Conflict("missing-local", null)
            if (current.revision != operation.baseRevision) return RemoteWrite.Conflict("stale-revision", current)
            val owner = dao.getByKey(operation.item.cardId, operation.item.variant, operation.item.condition)
            if (owner != null && owner.id != operation.item.id) {
                RemoteWrite.Conflict("duplicate-configuration", owner)
            } else {
                dao.upsertRemoteItem(operation.item.toRemoteEntity(revision = operation.baseRevision + 1))
                RemoteWrite.Applied
            }
        }

        is SyncOperation.Delete -> {
            val current = dao.getById(operation.itemId)
            if (current != null && current.revision != operation.baseRevision) {
                RemoteWrite.Conflict("stale-revision", current)
            } else {
                dao.deleteById(operation.itemId)
                RemoteWrite.Applied
            }
        }
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

private fun SyncOperation.toLocalItem(): SyncItem? = when (this) {
    is SyncOperation.Create -> item
    is SyncOperation.Replace -> item
    is SyncOperation.Delete -> null
}

private fun CollectionItemEntity.toSyncItem() = SyncItem(
    id = id, cardId = cardId, quantity = quantity, variant = variant, condition = condition,
    pricePaid = pricePaidAmount?.let { amount -> Money(amount, Currency.valueOf(checkNotNull(pricePaidCurrency))) },
    addedAt = addedAt,
)

private fun SyncOperation.itemId(): String = when (this) {
    is SyncOperation.Delete -> itemId
    is SyncOperation.Create -> item.id
    is SyncOperation.Replace -> item.id
}

private fun SyncOperation.toOutbox(identity: String) = when (this) {
    is SyncOperation.Create -> item.toOutbox(opId, identity, deviceId, "create", null)
    is SyncOperation.Replace -> item.toOutbox(opId, identity, deviceId, "replace", baseRevision)
    is SyncOperation.Delete -> SyncOutboxEntity(
        opId = opId, identity = identity, deviceId = deviceId, itemId = itemId, kind = "delete",
        baseRevision = baseRevision, revision = baseRevision, cardId = null, quantity = null,
        condition = null, variant = null, pricePaidAmount = null, pricePaidCurrency = null, addedAt = null,
    )
}

private fun SyncItem.toOutbox(opId: String, identity: String, deviceId: String, kind: String, baseRevision: Long?) =
    SyncOutboxEntity(
        opId = opId, identity = identity, deviceId = deviceId, itemId = id, kind = kind,
        baseRevision = baseRevision, revision = baseRevision?.plus(1) ?: 1,
        cardId = cardId, quantity = quantity, condition = condition.name, variant = variant.name,
        pricePaidAmount = pricePaid?.amount, pricePaidCurrency = pricePaid?.currency?.name, addedAt = addedAt,
    )

private fun SyncConflictDetail.toEntity() = SyncConflictEntity(
    id = id,
    identity = identity,
    sequence = sequence,
    itemId = itemId,
    reason = reason,
    localPayload = local?.let(::encodeSyncItem),
    remotePayload = encodeSyncOperation(requireNotNull(remote)),
)

private fun SyncConflictEntity.toDetail(): SyncConflictDetail? {
    if (source == "push") {
        val conflictId = serverConflictId ?: return null
        val rejected = rejectedOperation?.let { runCatching { decodeSyncOperation(it) }.getOrNull() }
        val localItem = localPayload?.let { runCatching { decodeSyncItem(it) }.getOrNull() }
        val cloud = cloudItem?.let { runCatching { decodePushCloudItem(Json.parseToJsonElement(it)) }.getOrNull() }
        val conflicting = conflictingItem?.let { runCatching { decodePushCloudItem(Json.parseToJsonElement(it)) }.getOrNull() }
        val choice = resolutionChoice?.let(::parsePushChoice)
        val storedResolutionId = resolutionId
        val storedResolutionResult = resolutionResult
        val receipt = if (storedResolutionId != null && choice != null && storedResolutionResult != null) {
            runCatching {
                PushResolutionReceipt(storedResolutionId, choice, Json.parseToJsonElement(storedResolutionResult).jsonObject)
            }.getOrNull()
        } else null
        val conflict = PushConflict(
            id = conflictId, opId = opId, itemId = itemId, reason = reason,
            deviceId = rejected?.deviceId ?: "unknown-device", rejectedOperation = rejected,
            cloudItem = cloud, conflictingItem = conflicting, highWater = observedHighWater ?: 0L, resolution = receipt,
        )
        return SyncConflictDetail(
            id = id, identity = identity, itemId = itemId, reason = reason, local = localItem,
            remote = rejected, source = "push", serverConflictId = conflictId, pushConflict = conflict,
        )
    }
    val operation = runCatching { decodeSyncOperation(remotePayload) }.getOrNull() ?: return null
    val localItem = localPayload?.let { runCatching { decodeSyncItem(it) }.getOrNull() }
    return SyncConflictDetail(id, identity, sequence, itemId, reason, localItem, operation)
}

private fun encodePushCloudItem(cloud: PushCloudItem): String = when (cloud) {
    is PushCloudItem.Present -> JsonObject(buildMap {
        put("itemId", JsonPrimitive(cloud.item.id)); put("cardId", JsonPrimitive(cloud.item.cardId))
        put("quantity", JsonPrimitive(cloud.item.quantity)); put("variant", JsonPrimitive(cloud.item.variant.name))
        put("condition", JsonPrimitive(cloud.item.condition.name)); put("addedAt", JsonPrimitive(cloud.item.addedAt))
        put("revision", JsonPrimitive(cloud.revision)); put("deleted", JsonPrimitive(false))
        put("pricePaid", cloud.item.pricePaid?.let { JsonObject(mapOf("amount" to JsonPrimitive((it.amount * 100).toLong()), "currency" to JsonPrimitive(it.currency.name))) } ?: JsonNull)
    }).toString()
    is PushCloudItem.Deleted -> JsonObject(mapOf(
        "itemId" to JsonPrimitive(cloud.itemId), "revision" to JsonPrimitive(cloud.revision), "deleted" to JsonPrimitive(true),
    )).toString()
    is PushCloudItem.Missing -> JsonObject(mapOf("itemId" to JsonPrimitive(cloud.itemId), "deleted" to JsonPrimitive(true), "revision" to JsonPrimitive(0))).toString()
}

private fun decodePushCloudItem(element: kotlinx.serialization.json.JsonElement?): PushCloudItem? {
    if (element == null || element is JsonNull) return null
    val row = element.jsonObject
    val itemId = row.getValue("itemId").jsonPrimitive.content
    val deleted = row.getValue("deleted").jsonPrimitive.content.toBooleanStrict()
    val revision = row.getValue("revision").jsonPrimitive.longOrNull ?: error("Invalid cloud revision")
    if (deleted) return if (revision == 0L) PushCloudItem.Missing(itemId) else PushCloudItem.Deleted(itemId, revision)
    val price = row["pricePaid"]?.takeUnless { it is JsonNull }?.jsonObject
    val item = SyncItem(
        id = itemId,
        cardId = row.getValue("cardId").jsonPrimitive.content,
        quantity = row.getValue("quantity").jsonPrimitive.intOrNull ?: error("Invalid cloud quantity"),
        variant = CardVariant.valueOf(row.getValue("variant").jsonPrimitive.content),
        condition = Condition.valueOf(row.getValue("condition").jsonPrimitive.content),
        pricePaid = price?.let { Money(it.getValue("amount").jsonPrimitive.longOrNull!!.toDouble() / 100.0, Currency.valueOf(it.getValue("currency").jsonPrimitive.content)) },
        addedAt = row.getValue("addedAt").jsonPrimitive.longOrNull ?: error("Invalid cloud timestamp"),
    )
    return PushCloudItem.Present(item, revision)
}

private fun parsePushChoice(value: String): PushConflictChoice? = when (value) {
    "keep-local" -> PushConflictChoice.KEEP_LOCAL
    "keep-cloud" -> PushConflictChoice.KEEP_CLOUD
    else -> null
}

private fun JsonObject.string(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(key: String): Long? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull

private fun encodeSyncItem(item: SyncItem): String = JsonObject(buildMap {
    put("id", JsonPrimitive(item.id)); put("cardId", JsonPrimitive(item.cardId))
    put("quantity", JsonPrimitive(item.quantity)); put("variant", JsonPrimitive(item.variant.name))
    put("condition", JsonPrimitive(item.condition.name)); put("addedAt", JsonPrimitive(item.addedAt))
    put("pricePaid", item.pricePaid?.let { JsonObject(mapOf("amount" to JsonPrimitive(it.amount), "currency" to JsonPrimitive(it.currency.name))) } ?: JsonNull)
}).toString()

private fun encodeSyncOperation(operation: SyncOperation): String = JsonObject(buildMap {
    put("opId", JsonPrimitive(operation.opId)); put("deviceId", JsonPrimitive(operation.deviceId))
    when (operation) {
        is SyncOperation.Create -> { put("kind", JsonPrimitive("create")); put("item", Json.parseToJsonElement(encodeSyncItem(operation.item))) }
        is SyncOperation.Replace -> {
            put("kind", JsonPrimitive("replace")); put("baseRevision", JsonPrimitive(operation.baseRevision))
            put("item", Json.parseToJsonElement(encodeSyncItem(operation.item)))
        }
        is SyncOperation.Delete -> {
            put("kind", JsonPrimitive("delete")); put("baseRevision", JsonPrimitive(operation.baseRevision))
            put("itemId", JsonPrimitive(operation.itemId))
        }
    }
}).toString()

private fun decodeSyncItem(payload: String): SyncItem {
    val row = Json.parseToJsonElement(payload).jsonObject
    val price = row["pricePaid"]?.takeUnless { it is JsonNull }?.jsonObject
    return SyncItem(
        id = row.getValue("id").jsonPrimitive.content,
        cardId = row.getValue("cardId").jsonPrimitive.content,
        quantity = row.getValue("quantity").jsonPrimitive.intOrNull ?: error("Invalid quantity"),
        variant = CardVariant.valueOf(row.getValue("variant").jsonPrimitive.content),
        condition = Condition.valueOf(row.getValue("condition").jsonPrimitive.content),
        pricePaid = price?.let { Money(it.getValue("amount").jsonPrimitive.content.toDouble(), Currency.valueOf(it.getValue("currency").jsonPrimitive.content)) },
        addedAt = row.getValue("addedAt").jsonPrimitive.longOrNull ?: error("Invalid timestamp"),
    )
}

private fun decodeSyncOperation(payload: String): SyncOperation {
    val row = Json.parseToJsonElement(payload).jsonObject
    val opId = row.getValue("opId").jsonPrimitive.content
    val deviceId = row.getValue("deviceId").jsonPrimitive.content
    return when (row.getValue("kind").jsonPrimitive.content) {
        "create" -> SyncOperation.Create(opId, deviceId, decodeSyncItem(row.getValue("item").toString()))
        "replace" -> SyncOperation.Replace(opId, deviceId, row.getValue("baseRevision").jsonPrimitive.longOrNull ?: error("Invalid revision"), decodeSyncItem(row.getValue("item").toString()))
        "delete" -> SyncOperation.Delete(opId, deviceId, row.getValue("baseRevision").jsonPrimitive.longOrNull ?: error("Invalid revision"), row.getValue("itemId").jsonPrimitive.content)
        else -> error("Unknown sync operation")
    }
}

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
