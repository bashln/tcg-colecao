package app.tcg.app.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the OTA flow as a small state machine: check for a stable release, download the
 * APK, run the defensive install pre-check, then stop at an install hand-off. It never installs
 * anything itself — the platform's PackageInstaller is the authority, triggered by the caller.
 * Checks and downloads are serialized (a second concurrent call is a no-op). A cancelled download
 * returns to `Ready` and rethrows, so cancellation never masquerades as success.
 */
class UpdateCoordinator(
    private val installedApp: InstalledAppInfo,
    private val fetchRelease: suspend () -> ReleaseFetch,
    private val downloadApk: suspend (GitHubRelease, ((Long, Long?) -> Unit)?) -> ApkDownload,
    private val readArchiveMetadata: suspend (File) -> ApkMetadata?,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val busy = AtomicBoolean(false)

    suspend fun check() {
        if (!busy.compareAndSet(false, true)) return
        try {
            _state.value = UpdateState.Checking
            when (val fetch = fetchRelease()) {
                ReleaseFetch.Offline -> _state.value = UpdateState.Error(UpdateErrorKind.Offline)
                is ReleaseFetch.RateLimited -> _state.value = UpdateState.Error(UpdateErrorKind.RateLimited)
                is ReleaseFetch.Invalid -> _state.value = UpdateState.Error(UpdateErrorKind.InvalidMetadata)
                is ReleaseFetch.Fetched -> selectFrom(fetch.releasesJson)
                is ReleaseFetch.NotModified -> selectFrom(fetch.cachedReleasesJson)
            }
        } finally {
            busy.set(false)
        }
    }

    suspend fun download() {
        val ready = _state.value as? UpdateState.Ready ?: return
        if (!busy.compareAndSet(false, true)) return
        try {
            _state.value = UpdateState.Downloading(ready.release, 0L, null)
            val result = try {
                downloadApk(ready.release) { received, total ->
                    _state.value = UpdateState.Downloading(ready.release, received, total)
                }
            } catch (cancelled: CancellationException) {
                _state.value = UpdateState.Ready(ready.release)
                throw cancelled
            }
            handleDownload(ready.release, result)
        } finally {
            busy.set(false)
        }
    }

    fun dismiss() {
        if (busy.get()) return
        _state.value = UpdateState.Idle
    }

    private fun selectFrom(releasesJson: String) {
        when (val selection = ReleaseParser.parse(releasesJson)) {
            is ReleaseSelection.Rejected -> _state.value = UpdateState.Error(UpdateErrorKind.InvalidMetadata)
            ReleaseSelection.NoStableRelease -> _state.value = UpdateState.NoUpdate
            is ReleaseSelection.Selected -> {
                when (val check = UpdateChecker.check(installedApp.versionCode, selection.release)) {
                    UpdateCheck.NoUpdate -> _state.value = UpdateState.NoUpdate
                    is UpdateCheck.Available -> _state.value = UpdateState.Ready(check.release)
                }
            }
        }
    }

    private suspend fun handleDownload(release: GitHubRelease, result: ApkDownload) {
        when (result) {
            is ApkDownload.Completed -> {
                val metadata = readArchiveMetadata(result.file)
                if (metadata == null) {
                    result.file.delete()
                    _state.value = UpdateState.Error(UpdateErrorKind.InvalidMetadata)
                    return
                }
                when (val validation = ApkValidator.validate(installedApp, metadata)) {
                    ApkValidation.Accepted -> _state.value = UpdateState.InstallReady(release, result.file)
                    is ApkValidation.Rejected -> {
                        result.file.delete()
                        _state.value = UpdateState.Incompatible(validation.reason)
                    }
                }
            }

            ApkDownload.Offline -> _state.value = UpdateState.Error(UpdateErrorKind.Offline)
            ApkDownload.HttpError,
            ApkDownload.IntegrityMismatch,
            ApkDownload.TooLarge,
            ApkDownload.InvalidUrl,
            -> _state.value = UpdateState.Error(UpdateErrorKind.DownloadFailed)
        }
    }
}
