package app.tcg.app.ui.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tcg.app.data.backup.BackupStatus
import app.tcg.app.data.backup.CollectionBackup
import app.tcg.app.data.backup.ImportPreview
import app.tcg.app.data.backup.ImportResult
import app.tcg.app.data.backup.ImportTicket
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.stats.CollectionStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    val stats: CollectionStats = CollectionStats.EMPTY,
    val loading: Boolean = true,
    val backupStatus: BackupStatus = BackupStatus.Unset,
    val encryptionEnabled: Boolean = false,
    val activePreview: ImportTicket? = null,
    val previewError: String? = null,
    val importMessage: String? = null,
    val importPasswordRequired: Boolean = false,
)

class ProfileViewModel(
    repository: CollectionRepository,
    private val backup: CollectionBackup? = null,
) : ViewModel() {

    private val activePreview = MutableStateFlow<ImportTicket?>(null)
    private val previewError = MutableStateFlow<String?>(null)
    private val importMessage = MutableStateFlow<String?>(null)
    private val importPasswordRequired = MutableStateFlow(false)
    private var pendingImportBytes: ByteArray? = null

    private val coreState = combine(
        repository.observe(),
        backup?.status ?: MutableStateFlow(BackupStatus.Unset),
        activePreview,
        previewError,
        importMessage,
    ) { entries, backupStatus, preview, error, message ->
        ProfileUiState(
            stats = CollectionStats.compute(entries, PriceSource.TCGPLAYER),
            loading = false,
            backupStatus = backupStatus,
            activePreview = preview,
            previewError = error,
            importMessage = message,
        )
    }

    private val stateWithImportPassword = combine(coreState, importPasswordRequired) { state, required ->
        state.copy(importPasswordRequired = required)
    }

    val state: StateFlow<ProfileUiState> = combine(
        stateWithImportPassword,
        backup?.encryptionEnabled ?: flowOf(false),
    ) { state, encrypted -> state.copy(encryptionEnabled = encrypted) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ProfileUiState())

    fun chooseBackupFolder(treeUri: Uri) {
        val backupService = backup ?: return
        viewModelScope.launch {
            backupService.chooseBackupFolder(treeUri.toString())
        }
    }

    fun reportBackupError(message: String) {
        importMessage.value = message
    }

    fun previewImport(bytes: ByteArray) {
        val backupService = backup ?: return
        viewModelScope.launch {
            previewError.value = null
            importMessage.value = null
            when (val preview = backupService.previewImport(bytes)) {
                is ImportPreview.Ready -> activePreview.value = preview.ticket
                is ImportPreview.Invalid -> previewError.value = preview.reason
                ImportPreview.PasswordRequired -> {
                    pendingImportBytes = bytes
                    importPasswordRequired.value = true
                }
            }
        }
    }

    fun previewEncryptedImport(passphrase: String) {
        val backupService = backup ?: return
        val bytes = pendingImportBytes ?: return
        viewModelScope.launch {
            previewError.value = null
            when (val preview = backupService.previewImport(bytes, passphrase.toCharArray())) {
                is ImportPreview.Ready -> {
                    pendingImportBytes = null
                    importPasswordRequired.value = false
                    activePreview.value = preview.ticket
                }
                is ImportPreview.Invalid -> previewError.value = preview.reason
                ImportPreview.PasswordRequired -> previewError.value = "Informe a senha desta cópia."
            }
        }
    }

    fun cancelEncryptedImport() {
        pendingImportBytes = null
        importPasswordRequired.value = false
        previewError.value = null
    }

    fun setBackupEncryptionPassphrase(passphrase: String?) {
        val backupService = backup ?: return
        val secret = passphrase?.toCharArray()
        viewModelScope.launch {
            val saved = try {
                backupService.setEncryptionPassphrase(secret)
            } finally {
                secret?.fill('\u0000')
            }
            importMessage.value = when {
                !saved && passphrase == null -> "Não foi possível desativar a proteção das cópias."
                !saved -> "Use uma senha de 12 a 256 caracteres e tente novamente."
                passphrase == null -> "A proteção foi desativada. A próxima cópia será sem senha."
                else -> "Proteção ativada. A próxima cópia será criptografada. Guarde a senha: ela não pode ser recuperada."
            }
        }
    }

    fun confirmImport() {
        val ticket = activePreview.value ?: return
        val backupService = backup ?: return
        viewModelScope.launch {
            when (val result = backupService.confirmImport(ticket)) {
                is ImportResult.Success -> {
                    activePreview.value = null
                    importMessage.value = "Cópia aplicada: ${result.summary.added} adicionadas, ${result.summary.merged} combinadas."
                }
                is ImportResult.ChangedSincePreview -> {
                    activePreview.value = null
                    previewError.value = "A coleção mudou após a prévia. Selecione o arquivo novamente."
                }
                is ImportResult.StorageError -> {
                    previewError.value = result.message
                }
            }
        }
    }

    fun cancelPreview() {
        activePreview.value = null
    }

    fun dismissMessages() {
        previewError.value = null
        importMessage.value = null
    }
}
