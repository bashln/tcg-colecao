package app.tcg.app.data.backup

import app.tcg.app.data.local.CollectionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

sealed interface BackupStatus {
    data object Unset : BackupStatus
    data class Pending(val treeUri: String) : BackupStatus
    data class Current(val treeUri: String, val lastGenerationAt: Long, val warning: String? = null) : BackupStatus
    data class Failed(val treeUri: String?, val reason: String) : BackupStatus
}

sealed interface ImportPreview {
    data class Ready(val ticket: ImportTicket) : ImportPreview
    data class Invalid(val reason: String) : ImportPreview
    data object PasswordRequired : ImportPreview
}

interface CollectionBackup {
    val status: Flow<BackupStatus>
    val encryptionEnabled: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(false)
    suspend fun chooseBackupFolder(treeUri: String)
    suspend fun previewImport(bytes: ByteArray): ImportPreview
    suspend fun previewImport(bytes: ByteArray, passphrase: CharArray?): ImportPreview = previewImport(bytes)
    suspend fun setEncryptionPassphrase(passphrase: CharArray?): Boolean = passphrase == null
    suspend fun confirmImport(ticket: ImportTicket): ImportResult
}

class RoomCollectionBackup(
    private val dao: CollectionDao,
    private val recoveryStore: RecoveryStore,
    private val backupManager: LocalBackupManager,
    private val passphrases: BackupPassphraseStore = NoBackupPassphraseStore,
) : CollectionBackup {

    override val encryptionEnabled: Flow<Boolean> = passphrases.enabled

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
        return previewImport(bytes, null)
    }

    override suspend fun previewImport(bytes: ByteArray, passphrase: CharArray?): ImportPreview {
        val payload = if (BackupEncryption.isEncrypted(bytes)) {
            val enteredByUser = passphrase != null
            val selected = passphrase ?: when (val saved = passphrases.read()) {
                is StoredBackupPassphrase.Available -> saved.value
                StoredBackupPassphrase.Disabled, StoredBackupPassphrase.Unavailable -> return ImportPreview.PasswordRequired
            }
            try {
                withContext(Dispatchers.Default) { BackupEncryption.decrypt(bytes, selected) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ex: Exception) {
                if (!enteredByUser) return ImportPreview.PasswordRequired
                return ImportPreview.Invalid(ex.message ?: "Senha incorreta ou arquivo criptografado inválido")
            }
        } else bytes
        val backup = try {
            WebBackupAdapter.parse(payload)
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

    override suspend fun setEncryptionPassphrase(passphrase: CharArray?): Boolean {
        if (passphrase != null) {
            try {
                BackupEncryption.validatePassphrase(passphrase)
            } catch (_: IllegalArgumentException) {
                passphrase.fill('\u0000')
                return false
            }
        }
        val saved = try {
            passphrases.write(passphrase)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            passphrase?.fill('\u0000')
        }
        if (saved) {
            dao.requestBackupRewrite()
            backupManager.runBackup()
        }
        return saved
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
