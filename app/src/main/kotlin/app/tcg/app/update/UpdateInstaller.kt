package app.tcg.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

sealed interface UpdateHandoff {
    data object HandedOff : UpdateHandoff
    data object NeedsInstallPermission : UpdateHandoff
    data object InvalidApk : UpdateHandoff
}

/**
 * Hands a validated APK from the private cache to the platform installer. Only a file that is
 * inside the app's cache and parses to this app's `applicationId` is eligible. When the
 * "install unknown apps" source permission is not yet granted, it reports that so the caller can
 * route the person to system settings. The hand-off is a user-facing `ACTION_VIEW` with a
 * read-only FileProvider content URI — never a silent install, downgrade, or uninstall. Signature,
 * package and version authority stay with Android's PackageInstaller; Play Protect is never
 * preflighted or bypassed.
 */
class UpdateInstaller(
    private val context: Context,
    private val canRequestInstalls: () -> Boolean,
    private val archivePackageName: (File) -> String?,
    private val launch: (Intent) -> Unit,
    private val contentUri: (File) -> Uri = { file ->
        FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
    },
) {

    fun installSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun handoff(apkFile: File): UpdateHandoff {
        if (!apkFile.exists() || !apkFile.isFile) return UpdateHandoff.InvalidApk
        if (!isWithinPrivateCache(apkFile)) return UpdateHandoff.InvalidApk
        if (archivePackageName(apkFile) != context.packageName) return UpdateHandoff.InvalidApk
        if (!canRequestInstalls()) return UpdateHandoff.NeedsInstallPermission

        val uri = contentUri(apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launch(intent)
        return UpdateHandoff.HandedOff
    }

    private fun isWithinPrivateCache(file: File): Boolean {
        val cache = runCatching { context.cacheDir.canonicalFile }.getOrNull() ?: return false
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return target.path == cache.path || target.path.startsWith(cache.path + File.separator)
    }

    companion object {
        const val FILE_PROVIDER_SUFFIX = ".fileprovider"
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
