package app.tcg.app.data.sync

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicReference

interface SyncLocalStore {
    suspend fun pending(identity: String): List<SyncOperation>
    suspend fun acknowledge(identity: String, opId: String): Boolean
    suspend fun applyRemote(identity: String, entry: SequencedOperation): RemoteApplyResult
    suspend fun conflicts(identity: String): List<SyncConflictDetail>
    suspend fun resolveConflict(identity: String, conflictId: String, resolution: SyncConflictResolution): SyncOperation?
    suspend fun finishConflict(identity: String, conflictId: String, operationId: String): Boolean
    suspend fun lastSequence(identity: String): Long
    suspend fun saveSequence(identity: String, sequence: Long): Boolean
}

enum class SyncConflictResolution { KeepLocal, KeepCloud }

data class SyncConflictDetail(
    val id: String = "",
    val identity: String = "",
    val sequence: Long = 0,
    val itemId: String,
    val reason: String,
    val local: SyncItem? = null,
    val remote: SyncOperation? = null,
)

sealed interface RemoteApplyResult {
    data object Applied : RemoteApplyResult
    data class Conflict(val detail: SyncConflictDetail) : RemoteApplyResult
}

sealed interface SyncResult {
    data class Complete(val applied: Int, val pending: Int) : SyncResult
    data class Conflicts(val count: Int, val pending: Int?, val details: List<SyncConflictDetail> = emptyList()) : SyncResult
    data class Offline(val pending: Int?) : SyncResult
    data class Error(val pending: Int?) : SyncResult
}

class CollectionSyncCoordinator(
    private val transport: SyncTransport,
    private val local: SyncLocalStore,
) {
    private val identity = AtomicReference<String?>(null)

    fun bindIdentity(nextIdentity: String?) {
        identity.set(nextIdentity)
    }

    suspend fun syncOnce(): SyncResult {
        val boundIdentity = identity.get() ?: return SyncResult.Offline(0)
        return try {
            val pending = local.pending(boundIdentity)
            if (identity.get() != boundIdentity) return SyncResult.Offline(pending.size)
            val outcomes = transport.push(boundIdentity, pending)
            var applied = 0
            val conflicts = mutableListOf<SyncConflictDetail>()
            pending.zip(outcomes).forEach { (operation, outcome) ->
                if (identity.get() != boundIdentity) return SyncResult.Offline(pending.size)
                when (outcome) {
                    is PushOutcome.Conflict -> conflicts += SyncConflictDetail(
                        itemId = operation.itemId(),
                        reason = outcome.reason,
                        local = operation.localItem(),
                    )
                    is PushOutcome.Applied, is PushOutcome.AlreadyApplied -> {
                        if (!local.acknowledge(boundIdentity, operation.opId)) {
                            return SyncResult.Error(pending.size)
                        }
                        if (outcome is PushOutcome.Applied) applied++
                    }
                }
            }

            if (identity.get() != boundIdentity) return SyncResult.Offline(pending.size)
            val page = transport.pull(boundIdentity, local.lastSequence(boundIdentity))
            if (identity.get() != boundIdentity) return SyncResult.Offline(pending.size)
            for (entry in page.operations) {
                if (identity.get() != boundIdentity) return SyncResult.Offline(pending.size)
                when (val result = local.applyRemote(boundIdentity, entry)) {
                    RemoteApplyResult.Applied -> Unit
                    is RemoteApplyResult.Conflict -> return SyncResult.Conflicts(
                        count = 1,
                        pending = local.pending(boundIdentity).size,
                        details = listOf(result.detail),
                    )
                }
            }
            if (identity.get() != boundIdentity || !local.saveSequence(boundIdentity, page.highWaterMark)) {
                return SyncResult.Offline(pending.size)
            }

            val remaining = local.pending(boundIdentity).size
            val storedConflicts = local.conflicts(boundIdentity)
            if (conflicts.isNotEmpty() || storedConflicts.isNotEmpty()) SyncResult.Conflicts(conflicts.size + storedConflicts.size, remaining, conflicts + storedConflicts)
            else SyncResult.Complete(applied, remaining)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val remaining = if (identity.get() == boundIdentity) runCatching { local.pending(boundIdentity).size }.getOrNull() else null
            SyncResult.Error(remaining)
        }
    }

    suspend fun resolveConflict(conflictId: String, resolution: SyncConflictResolution): SyncResult {
        val boundIdentity = identity.get() ?: return SyncResult.Offline(0)
        return try {
            val operation = local.resolveConflict(boundIdentity, conflictId, resolution)
            if (identity.get() != boundIdentity) return SyncResult.Offline(local.pending(boundIdentity).size)
            if (operation != null) {
                val outcome = transport.push(boundIdentity, listOf(operation)).singleOrNull()
                    ?: return SyncResult.Error(local.pending(boundIdentity).size)
                if (identity.get() != boundIdentity) return SyncResult.Offline(local.pending(boundIdentity).size)
                if (outcome !is PushOutcome.Applied && outcome !is PushOutcome.AlreadyApplied) {
                    return SyncResult.Conflicts(1, local.pending(boundIdentity).size, local.conflicts(boundIdentity))
                }
                if (!local.acknowledge(boundIdentity, operation.opId) ||
                    !local.finishConflict(boundIdentity, conflictId, operation.opId)) {
                    return SyncResult.Error(local.pending(boundIdentity).size)
                }
            }
            syncOnce()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SyncResult.Error(runCatching { local.pending(boundIdentity).size }.getOrNull())
        }
    }
}

private fun SyncOperation.itemId(): String = when (this) {
    is SyncOperation.Delete -> itemId
    is SyncOperation.Create -> item.id
    is SyncOperation.Replace -> item.id
}

private fun SyncOperation.localItem(): SyncItem? = when (this) {
    is SyncOperation.Delete -> null
    is SyncOperation.Create -> item
    is SyncOperation.Replace -> item
}
