package app.tcg.app.update

/**
 * Decides whether a remote stable release is a newer version than the installed one.
 * The decision is driven purely by `versionCode`, so an update is offered only when the
 * remote code is strictly greater — equal or lower never offers an update (no downgrade).
 */
object UpdateChecker {
    fun check(currentVersionCode: Long, remote: GitHubRelease?): UpdateCheck =
        if (remote != null && remote.versionCode > currentVersionCode) {
            UpdateCheck.Available(remote)
        } else {
            UpdateCheck.NoUpdate
        }
}
