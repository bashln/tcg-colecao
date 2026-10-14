package app.tcg.app.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Platform (PackageManager) reads for the OTA pre-check. Used on-device, not in unit tests. */
object OtaPlatform {

    fun installedSignatureDigest(context: Context): String? {
        val signers = signingSigners(
            context = context,
            packageName = context.packageName,
            flags = signingFlags(),
        ) ?: return null
        val first = signers.firstOrNull() ?: return null
        return sha256Hex(first.toByteArray())
    }

    fun archiveMetadata(context: Context, file: File): ApkMetadata? {
        if (!file.exists()) return null
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags())
            ?: return null
        val packageName = info.packageName ?: return null
        val signature = signingSignersFromInfo(info)?.firstOrNull()?.toByteArray()?.let { sha256Hex(it) }
        return ApkMetadata(packageName, longVersionCode(info), signature)
    }

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun signingSigners(context: Context, packageName: String, flags: Int): Array<android.content.pm.Signature>? {
        val info = context.packageManager.getPackageInfo(packageName, flags)
        return signingSignersFromInfo(info)
    }

    @Suppress("DEPRECATION")
    private fun signingSignersFromInfo(info: android.content.pm.PackageInfo): Array<android.content.pm.Signature>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
    }

    @Suppress("DEPRECATION")
    private fun longVersionCode(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
