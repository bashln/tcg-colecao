package app.tcg.app.update

import java.io.File

/** A stable GitHub release with a resolved APK asset. */
data class GitHubRelease(
    val tagName: String,
    val versionName: String,
    val versionCode: Long,
    val releaseNotes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val apkSha256: String?,
    val publishedAt: String?,
)

sealed interface ReleaseSelection {
    data class Selected(val release: GitHubRelease) : ReleaseSelection
    data class Rejected(val error: OtaError) : ReleaseSelection
    data object NoStableRelease : ReleaseSelection
}

sealed interface OtaError {
    data object MalformedTag : OtaError
    data object MissingApkAsset : OtaError
    data object MultipleApkAssets : OtaError
    data object VersionOverflow : OtaError
    data object InvalidJson : OtaError
}

sealed interface UpdateCheck {
    data class Available(val release: GitHubRelease) : UpdateCheck
    data object NoUpdate : UpdateCheck
}

/** Result of a metadata fetch. `NotModified` carries the cached body so a 304 never strands the caller. */
sealed interface ReleaseFetch {
    data class Fetched(val etag: String?, val releasesJson: String) : ReleaseFetch
    data class NotModified(val cachedReleasesJson: String) : ReleaseFetch
    data object Offline : ReleaseFetch
    data class RateLimited(val resetEpochSeconds: Long?) : ReleaseFetch
    data class Invalid(val error: OtaError) : ReleaseFetch
}

/** UI-facing update state. `InstallReady` is a hand-off — the coordinator never installs itself. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object NoUpdate : UpdateState
    data class Ready(val release: GitHubRelease) : UpdateState
    data class Downloading(val release: GitHubRelease, val received: Long, val total: Long?) : UpdateState
    data class InstallReady(val release: GitHubRelease, val apkFile: File) : UpdateState
    data class Incompatible(val reason: ApkRejectReason) : UpdateState
    data class Error(val kind: UpdateErrorKind) : UpdateState
}

sealed interface UpdateErrorKind {
    data object Offline : UpdateErrorKind
    data object RateLimited : UpdateErrorKind
    data object DownloadFailed : UpdateErrorKind
    data object InvalidMetadata : UpdateErrorKind
}
