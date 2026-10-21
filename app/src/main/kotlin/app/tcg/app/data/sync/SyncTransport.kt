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
    /** The operation the server echoed back; the coordinator attributes outcomes by it. */
    val opId: String?

    data class Applied(val sequence: Long, override val opId: String? = null) : PushOutcome
    data class AlreadyApplied(val sequence: Long, override val opId: String? = null) : PushOutcome
    data class Conflict(
        val reason: String,
        override val opId: String? = null,
        val conflictId: String? = null,
        val highWater: Long? = null,
        val rejectedOperation: SyncOperation? = null,
        val cloudItem: PushCloudItem? = null,
        val conflictingItem: PushCloudItem? = null,
    ) : PushOutcome
    data class Rejected(val reason: String, override val opId: String? = null, val conflictId: String? = null) : PushOutcome
}

sealed interface PushCloudItem {
    data class Present(val item: SyncItem, val revision: Long) : PushCloudItem
    data class Deleted(val itemId: String, val revision: Long) : PushCloudItem
    data class Missing(val itemId: String) : PushCloudItem
}

data class PushConflict(
    val id: String,
    val opId: String?,
    val itemId: String,
    val reason: String,
    val deviceId: String,
    val rejectedOperation: SyncOperation?,
    val cloudItem: PushCloudItem?,
    val conflictingItem: PushCloudItem?,
    val highWater: Long,
    val resolution: PushResolutionReceipt?,
)

data class PushResolutionReceipt(val id: String, val choice: PushConflictChoice, val result: Map<String, kotlinx.serialization.json.JsonElement>)

enum class PushConflictChoice(val wireValue: String) { KEEP_LOCAL("keep-local"), KEEP_CLOUD("keep-cloud") }

data class PushResolutionResponse(
    val status: String,
    val conflictId: String,
    val choice: PushConflictChoice? = null,
    val resolutionId: String? = null,
    val reason: String? = null,
    val result: Map<String, kotlinx.serialization.json.JsonElement>? = null,
)

interface SyncTransport {
    suspend fun push(identity: String, operations: List<SyncOperation>): List<PushOutcome>
    suspend fun pull(identity: String, afterSequence: Long): PullPage
    suspend fun listPushConflicts(expectedIdentity: String): List<PushConflict> = emptyList()
    suspend fun resolvePushConflict(conflictId: String, choice: PushConflictChoice, deviceId: String, expectedIdentity: String): PushResolutionResponse = unsupportedPushConflicts()
    suspend fun acknowledgePushConflictResolution(conflictId: String, resolutionId: String, expectedIdentity: String): PushResolutionResponse = unsupportedPushConflicts()
}

private fun unsupportedPushConflicts(): Nothing = throw SupabaseSyncException(
    SyncErrorKind.UNAVAILABLE,
    "Este transporte não oferece recuperação de conflitos da nuvem",
)
