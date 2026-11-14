package app.tcg.app.ui.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tcg.app.data.backup.BackupStatus
import app.tcg.app.data.backup.CollectionBackup
import app.tcg.app.data.backup.ImportPreview
import app.tcg.app.data.backup.ImportResult
import app.tcg.app.data.backup.ImportTicket
import app.tcg.app.data.preferences.AppPreferences
import app.tcg.app.data.prices.PriceRefresher
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.stats.CollectionStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

data class ProfileUiState(
    val stats: CollectionStats = CollectionStats.EMPTY,
    val loading: Boolean = true,
    val backupStatus: BackupStatus = BackupStatus.Unset,
    val encryptionEnabled: Boolean = false,
    val activePreview: ImportTicket? = null,
    val previewError: String? = null,
    val importMessage: String? = null,
    val importPasswordRequired: Boolean = false,
    val priceSource: PriceSource = PriceSource.TCGPLAYER,
    val refreshingPrices: Boolean = false,
    val refreshPricesMessage: String? = null,
)

class ProfileViewModel(
    private val repository: CollectionRepository,
    private val backup: CollectionBackup? = null,
    private val preferences: AppPreferences? = null,
    private val priceRefresher: PriceRefresher? = null,
) : ViewModel() {

    private data class RefreshStatus(val refreshing: Boolean = false, val message: String? = null)
    private val refreshStatus = MutableStateFlow(RefreshStatus())

    /** Fetches the catalog and Jornada prices on demand, so a card never waits for the daily worker. */
    fun refreshPrices() {
        val refresher = priceRefresher ?: return
        if (refreshStatus.value.refreshing) return
        viewModelScope.launch {
            refreshStatus.value = RefreshStatus(refreshing = true)
            val report = try {
                refresher.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (report != null) refreshedEntries.value = repository.list()
            refreshStatus.value = RefreshStatus(
                refreshing = false,
                message = if (report == null) {
                    "Não foi possível atualizar os preços agora."
                } else {
                    "Preços atualizados: ${report.refreshed} de ${report.requested} cartas."
                },
            )
        }
    }

    val selectedPriceSource: StateFlow<PriceSource> =
        preferences?.priceSource ?: MutableStateFlow(PriceSource.TCGPLAYER)

    fun onPriceSourceSelected(source: PriceSource) {
        preferences?.setPriceSource(source)
    }

    private val activePreview = MutableStateFlow<ImportTicket?>(null)
    private val previewError = MutableStateFlow<String?>(null)
    private val importMessage = MutableStateFlow<String?>(null)
    private val importPasswordRequired = MutableStateFlow(false)
    private var pendingImportBytes: ByteArray? = null

    /** Re-read after a price refresh: the collection flow does not re-emit when only prices change. */
    private val refreshedEntries = MutableStateFlow<List<CollectionEntry>?>(null)

    private val statsFlow = combine(repository.observe(), selectedPriceSource, refreshedEntries) { entries, source, override ->
        Pair(CollectionStats.compute(override ?: entries, source), source)
    }

    private val coreState = combine(
        statsFlow,
        backup?.status ?: MutableStateFlow(BackupStatus.Unset),
        activePreview,
        previewError,
        importMessage,
    ) { (stats, source), backupStatus, preview, error, message ->
        ProfileUiState(
            stats = stats,
            loading = false,
            backupStatus = backupStatus,
            activePreview = preview,
            previewError = error,
            importMessage = message,
            priceSource = source,
        )
    }

    private val stateWithImportPassword = combine(coreState, importPasswordRequired) { state, required ->
        state.copy(importPasswordRequired = required)
    }

    val state: StateFlow<ProfileUiState> = combine(
        stateWithImportPassword,
        backup?.encryptionEnabled ?: flowOf(false),
        refreshStatus,
    ) { state, encrypted, refresh ->
        state.copy(
            encryptionEnabled = encrypted,
            refreshingPrices = refresh.refreshing,
            refreshPricesMessage = refresh.message,
        )
    }
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
