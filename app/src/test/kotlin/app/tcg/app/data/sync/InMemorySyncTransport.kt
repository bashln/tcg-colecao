package app.tcg.app.data.sync

internal class InMemorySyncTransport(failNextPush: Boolean = false) : SyncTransport {
    private var failPush = failNextPush
    private val accounts = mutableMapOf<String, Account>()
    var onPush: (() -> Unit)? = null
    var pushCount = 0

    override suspend fun push(identity: String, operations: List<SyncOperation>): List<PushOutcome> {
        if (failPush) {
            failPush = false
            throw IllegalStateException("Transport unavailable")
        }
        pushCount++
        onPush?.also { onPush = null }?.invoke()
        val account = accounts.getOrPut(identity, ::Account)
        return operations.map { operation ->
            val prior = account.applied[operation.opId]
            if (prior != null) {
                if (prior.first != operation) PushOutcome.Conflict("id-collision")
                else PushOutcome.AlreadyApplied(prior.second)
            } else {
                val outcome = account.apply(operation)
                if (outcome is PushOutcome.Applied) {
                    account.sequence += 1
                    account.applied[operation.opId] = operation to account.sequence
                    account.log += SequencedOperation(account.sequence, operation)
                    PushOutcome.Applied(account.sequence)
                } else outcome
            }
        }
    }

    override suspend fun pull(identity: String, afterSequence: Long): PullPage {
        val account = accounts.getOrPut(identity, ::Account)
        return PullPage(account.log.filter { it.sequence > afterSequence }, account.sequence)
    }

    fun sequence(identity: String): Long = accounts[identity]?.sequence ?: 0L

    private class Account {
        var sequence = 0L
        val rows = mutableMapOf<String, Pair<Long, SyncItem?>>()
        val applied = mutableMapOf<String, Pair<SyncOperation, Long>>()
        val log = mutableListOf<SequencedOperation>()

        fun apply(operation: SyncOperation): PushOutcome {
            when (operation) {
                is SyncOperation.Create -> {
                    if (rows.containsKey(operation.item.id)) return PushOutcome.Conflict("stale")
                    if (rows.values.any { it.second?.sameConfiguration(operation.item) == true }) {
                        return PushOutcome.Conflict("duplicate-configuration")
                    }
                    rows[operation.item.id] = 1L to operation.item
                }
                is SyncOperation.Replace -> {
                    val current = rows[operation.item.id] ?: return PushOutcome.Conflict("missing")
                    if (current.first != operation.baseRevision) return PushOutcome.Conflict("stale")
                    if (current.second == null) return PushOutcome.Conflict("deleted")
                    rows[operation.item.id] = current.first + 1 to operation.item
                }
                is SyncOperation.Delete -> {
                    val current = rows[operation.itemId] ?: return PushOutcome.Conflict("missing")
                    if (current.first != operation.baseRevision) return PushOutcome.Conflict("stale")
                    if (current.second == null) return PushOutcome.Conflict("deleted")
                    rows[operation.itemId] = current.first + 1 to null
                }
            }
            return PushOutcome.Applied(0)
        }

        private fun SyncItem.sameConfiguration(other: SyncItem) =
            cardId == other.cardId && variant == other.variant && condition == other.condition
    }
}
