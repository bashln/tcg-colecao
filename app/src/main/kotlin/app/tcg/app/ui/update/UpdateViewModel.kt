package app.tcg.app.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tcg.app.update.UpdateCoordinator
import app.tcg.app.update.UpdateHandoff
import app.tcg.app.update.UpdateState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Bridges the update UI to the [UpdateCoordinator] and the platform [installHandoff]. Checking and
 * downloading are user-initiated (no background service, no check on every launch — protects the
 * GitHub API quota). A release is never downloaded or installed until the person accepts; the
 * install action only ever hands the validated APK to the platform installer, and routes to system
 * settings when the "install unknown apps" source permission is missing. Cancelling a download
 * leaves no install.
 */
class UpdateViewModel(
    private val coordinator: UpdateCoordinator,
    private val installHandoff: (File) -> UpdateHandoff,
    private val openInstallSettings: () -> Unit,
) : ViewModel() {

    val state: StateFlow<UpdateState> = coordinator.state

    private val _installBlocked = MutableStateFlow(false)
    val installBlocked: StateFlow<Boolean> = _installBlocked.asStateFlow()

    private var downloadJob: Job? = null

    fun check() {
        viewModelScope.launch { coordinator.check() }
    }

    fun acceptAndDownload() {
        if (downloadJob?.isActive == true) return
        _installBlocked.value = false
        downloadJob = viewModelScope.launch { coordinator.download() }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun install() {
        val ready = state.value as? UpdateState.InstallReady ?: return
        when (installHandoff(ready.apkFile)) {
            UpdateHandoff.HandedOff -> Unit
            UpdateHandoff.NeedsInstallPermission -> openInstallSettings()
            UpdateHandoff.InvalidApk -> _installBlocked.value = true
        }
    }

    fun dismissError() {
        _installBlocked.value = false
        coordinator.dismiss()
    }
}
