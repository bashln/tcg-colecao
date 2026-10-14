package app.tcg.app.update

/** Parsed metadata of a downloaded APK archive (from `PackageManager.getPackageArchiveInfo`). */
data class ApkMetadata(
    val applicationId: String,
    val versionCode: Long,
    val signatureDigest: String?,
)

/** Metadata of the currently installed app, used as the compatibility baseline. */
data class InstalledAppInfo(
    val applicationId: String,
    val versionCode: Long,
    val signatureDigest: String?,
)

sealed interface ApkValidation {
    data object Accepted : ApkValidation
    data class Rejected(val reason: ApkRejectReason) : ApkValidation
}

sealed interface ApkRejectReason {
    data object WrongApplicationId : ApkRejectReason
    data object NotNewerVersion : ApkRejectReason
    data object MissingSignature : ApkRejectReason
    data object SignatureMismatch : ApkRejectReason
}

/**
 * Defensive pre-check before handing an APK to the platform installer. It only accepts the
 * same application id, the same signing certificate, and a strictly newer versionCode (no
 * downgrade). The authoritative enforcement stays with Android's `PackageInstaller`, which
 * re-validates identity, signature and version at install time.
 */
object ApkValidator {
    fun validate(installed: InstalledAppInfo, archive: ApkMetadata): ApkValidation {
        if (archive.applicationId != installed.applicationId) {
            return ApkValidation.Rejected(ApkRejectReason.WrongApplicationId)
        }
        if (archive.versionCode <= installed.versionCode) {
            return ApkValidation.Rejected(ApkRejectReason.NotNewerVersion)
        }
        val archiveSignature = archive.signatureDigest
        val installedSignature = installed.signatureDigest
        if (archiveSignature.isNullOrBlank() || installedSignature.isNullOrBlank()) {
            return ApkValidation.Rejected(ApkRejectReason.MissingSignature)
        }
        if (!archiveSignature.equals(installedSignature, ignoreCase = true)) {
            return ApkValidation.Rejected(ApkRejectReason.SignatureMismatch)
        }
        return ApkValidation.Accepted
    }
}
