package app.tcg.app.data.sync

import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Money

data class SyncItem(
    val id: String,
    val cardId: String,
    val quantity: Int,
    val variant: CardVariant,
    val condition: Condition,
    val pricePaid: Money?,
    val addedAt: Long,
)

sealed interface SyncOperation {
    val opId: String
    val deviceId: String

    data class Create(
        override val opId: String,
        override val deviceId: String,
        val item: SyncItem,
    ) : SyncOperation

    data class Replace(
        override val opId: String,
        override val deviceId: String,
        val baseRevision: Long,
        val item: SyncItem,
    ) : SyncOperation

    data class Delete(
        override val opId: String,
        override val deviceId: String,
        val baseRevision: Long,
        val itemId: String,
    ) : SyncOperation
}

data class SequencedOperation(val sequence: Long, val operation: SyncOperation)

data class PullPage(val operations: List<SequencedOperation>, val highWaterMark: Long)

sealed interface PushOutcome {
    data class Applied(val sequence: Long) : PushOutcome
    data class AlreadyApplied(val sequence: Long) : PushOutcome
    data class Conflict(val reason: String) : PushOutcome
}

interface SyncTransport {
    suspend fun push(identity: String, operations: List<SyncOperation>): List<PushOutcome>
    suspend fun pull(identity: String, afterSequence: Long): PullPage
}
