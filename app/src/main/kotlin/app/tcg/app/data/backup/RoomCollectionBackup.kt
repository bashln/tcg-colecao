package app.tcg.app.data.backup

import app.tcg.app.data.local.CollectionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface BackupStatus {
    data object Unset : BackupStatus
    data class Pending(val treeUri: String) : BackupStatus
    data class Current(val treeUri: String, val lastGenerationAt: Long, val warning: String? = null) : BackupStatus
    data class Failed(val treeUri: String?, val reason: String) : BackupStatus
}

sealed interface ImportPreview {
    data class Ready(val ticket: ImportTicket) : ImportPreview
    data class Invalid(val reason: String) : ImportPreview
}

interface CollectionBackup {
    val status: Flow<BackupStatus>
    suspend fun chooseBackupFolder(treeUri: String)
    suspend fun previewImport(bytes: ByteArray): ImportPreview
    suspend fun confirmImport(ticket: ImportTicket): ImportResult
}

class RoomCollectionBackup(
    private val dao: CollectionDao,
    private val recoveryStore: RecoveryStore,
    private val backupManager: LocalBackupManager,
) : CollectionBackup {

    override val status: Flow<BackupStatus> = dao.observeBackupState().map { state ->
        if (state == null || state.treeUri == null) {
            BackupStatus.Unset
        } else if (state.failure != null) {
            BackupStatus.Failed(state.treeUri, state.failure)
        } else if (state.pendingRevision > state.savedRevision) {
            BackupStatus.Pending(state.treeUri)
        } else {
            val generations = dao.listGenerations(state.treeUri)
            val lastTime = generations.firstOrNull()?.createdAt ?: 0L
            BackupStatus.Current(state.treeUri, lastTime, state.retentionWarning)
        }
    }

    override suspend fun chooseBackupFolder(treeUri: String) {
        dao.setBackupFolder(treeUri)
        backupManager.runBackup()
    }

    override suspend fun previewImport(bytes: ByteArray): ImportPreview {
        val backup = try {
            WebBackupAdapter.parse(bytes)
        } catch (ex: Exception) {
            return ImportPreview.Invalid(ex.message ?: "Arquivo inválido")
        }

        val items = dao.listItems()
        val markers = dao.listImportMarkers().associate { it.itemId to it.signature }
        val binding = dao.getSyncBinding()
        val cardIds = dao.listAllCards().map { it.id }.toSet()

        val localState = ImportLocalState(
            items = items,
            markers = markers,
            binding = binding,
            cardIds = cardIds,
        )

        return try {
            val ticket = ImportPlanner.createTicket(backup, localState)
            ImportPreview.Ready(ticket)
        } catch (ex: Exception) {
            ImportPreview.Invalid(ex.message ?: "Conflito na importação")
        }
    }

    override suspend fun confirmImport(ticket: ImportTicket): ImportResult {
        val currentItems = dao.listItems()
        val allCards = dao.listAllCards().associateBy { it.id }
        val allPrices = dao.listAllPrices().groupBy { it.cardId }

        val recoveryEntries = currentItems.mapNotNull { item ->
            val card = allCards[item.cardId] ?: return@mapNotNull null
            val prices = allPrices[item.cardId] ?: emptyList()
            WebBackupAdapter.fromRoom(item, card, prices)
        }

        val recoveryOk = recoveryStore.writeAndVerifyRecovery(recoveryEntries)
        if (!recoveryOk) {
            return ImportResult.StorageError("Falha ao gravar cópia de recuperação")
        }

        val outcome = dao.applyImport(ticket)
        if (outcome is ImportResult.Success) {
            backupManager.runBackup()
        }
        return outcome
    }
}
