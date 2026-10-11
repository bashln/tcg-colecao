package app.tcg.app.data.sync

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicReference

interface SyncLocalStore {
    suspend fun pending(identity: String): List<SyncOperation>
    suspend fun acknowledge(identity: String, opId: String): Boolean
    suspend fun applyRemote(identity: String, entry: SequencedOperation): Boolean
    suspend fun lastSequence(identity: String): Long
    suspend fun saveSequence(identity: String, sequence: Long): Boolean
}

sealed interface SyncResult {
    data class Complete(val applied: Int, val pending: Int) : SyncResult
    data class Conflicts(val count: Int, val pending: Int) : SyncResult
    data class Offline(val pending: Int) : SyncResult
    data class Error(val pending: Int) : SyncResult
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
            var conflicts = 0
            pending.zip(outcomes).forEach { (operation, outcome) ->
                if (identity.get() != boundIdentity) return SyncResult.Offline(pending.size)
                when (outcome) {
                    is PushOutcome.Conflict -> conflicts++
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
                if (!local.applyRemote(boundIdentity, entry)) return SyncResult.Error(local.pending(boundIdentity).size)
            }
            if (identity.get() != boundIdentity || !local.saveSequence(boundIdentity, page.highWaterMark)) {
                return SyncResult.Offline(pending.size)
            }

            val remaining = local.pending(boundIdentity).size
            if (conflicts > 0) SyncResult.Conflicts(conflicts, remaining)
            else SyncResult.Complete(applied, remaining)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val remaining = if (identity.get() == boundIdentity) local.pending(boundIdentity).size else 0
            SyncResult.Error(remaining)
        }
    }
}
