package app.tcg.app.data.backup

import app.tcg.app.data.local.CollectionItemEntity
import app.tcg.app.data.local.SyncBindingEntity

data class ImportSummary(val added: Int, val merged: Int, val unchanged: Int)

sealed interface ImportResult {
    data class Success(val summary: ImportSummary) : ImportResult
    data object ChangedSincePreview : ImportResult
    data class StorageError(val message: String) : ImportResult
}

sealed interface ImportAction {
    data class Insert(val entry: WebEntry) : ImportAction
    data class Merge(val localItemId: String, val entry: WebEntry) : ImportAction
    data class Unchanged(val sourceItemId: String) : ImportAction
}

data class ImportLocalState(
    val items: List<CollectionItemEntity>,
    val markers: Map<String, String>,
    val binding: SyncBindingEntity?,
    val cardIds: Set<String>,
)

class ImportTicket internal constructor(
    internal val backup: ValidatedWebBackup,
    internal val expectedLocalState: ImportLocalState,
    internal val actions: List<ImportAction>,
    val summary: ImportSummary,
)

val CollectionItemEntity.configurationKey: String
    get() = "$cardId\u001f${variant.name}\u001f${condition.name}"

object ImportPlanner {
    fun createTicket(
        backup: ValidatedWebBackup,
        localState: ImportLocalState,
    ): ImportTicket {
        val actions = plan(backup, localState)
        val added = actions.count { it is ImportAction.Insert }
        val merged = actions.count { it is ImportAction.Merge }
        val unchanged = actions.count { it is ImportAction.Unchanged }
        return ImportTicket(
            backup = backup,
            expectedLocalState = localState,
            actions = actions,
            summary = ImportSummary(added = added, merged = merged, unchanged = unchanged),
        )
    }

    fun plan(
        backup: ValidatedWebBackup,
        localState: ImportLocalState,
    ): List<ImportAction> {
        val itemsById = localState.items.associateBy { it.id }
        val itemsByConfig = localState.items.associateBy { it.configurationKey }

        return backup.entries.map { entry ->
            val sourceItem = entry.item
            val existingMarkerSig = localState.markers[sourceItem.id]
            if (existingMarkerSig != null) {
                val currentSig = WebBackupAdapter.itemSignature(sourceItem)
                if (existingMarkerSig != currentSig) {
                    throw InvalidBackupException("Conflito no item importado ${sourceItem.id}")
                }
                return@map ImportAction.Unchanged(sourceItem.id)
            }

            val localWithSameId = itemsById[sourceItem.id]
            if (localWithSameId != null) {
                val matchesLocal = localWithSameId.cardId == sourceItem.cardId &&
                    localWithSameId.quantity == sourceItem.quantity &&
                    localWithSameId.variant == sourceItem.variant &&
                    localWithSameId.condition == sourceItem.condition &&
                    localWithSameId.pricePaidAmount == sourceItem.pricePaid?.amount &&
                    localWithSameId.pricePaidCurrency == sourceItem.pricePaid?.currency?.name &&
                    localWithSameId.addedAt == sourceItem.addedAt

                if (!matchesLocal) {
                    throw InvalidBackupException("Conflito de ID de item ${sourceItem.id}")
                }
                return@map ImportAction.Unchanged(sourceItem.id)
            }

            val localWithSameConfig = itemsByConfig[sourceItem.configurationKey]
            if (localWithSameConfig != null) {
                val newQuantity = localWithSameConfig.quantity + sourceItem.quantity
                if (newQuantity > 100_000) {
                    throw InvalidBackupException("Quantidade combinada excede o limite")
                }
                return@map ImportAction.Merge(localWithSameConfig.id, entry)
            }

            ImportAction.Insert(entry)
        }
    }
}
