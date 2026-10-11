package app.tcg.app.update

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
