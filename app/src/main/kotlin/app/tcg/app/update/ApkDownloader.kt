package app.tcg.app.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.MessageDigest
import java.util.UUID

sealed interface ApkDownload {
    data class Completed(val file: File, val sizeBytes: Long, val sha256: String) : ApkDownload
    data object InvalidUrl : ApkDownload
    data object HttpError : ApkDownload
    data object IntegrityMismatch : ApkDownload
    data object TooLarge : ApkDownload
    data object Offline : ApkDownload
}

/**
 * Downloads an APK asset to a private cache directory with integrity checks. It only accepts
 * an `https` URL and sends a bare GET (no Authorization or API headers), so no secret can leak
 * to a redirect host such as `githubusercontent.com`. Size is bounded to avoid disk exhaustion,
 * and the exact size plus SHA-256 are verified when provided. Any non-success — HTTP error,
 * integrity mismatch, oversize, or cancellation — removes the partial file via `finally`, so a
 * failed download never leaves residue. The actual install authority stays with Android's
 * PackageInstaller.
 */
class ApkDownloader(
    private val httpClient: HttpClient,
    private val cacheDir: File,
) {

    suspend fun download(
        url: String,
        expectedSizeBytes: Long? = null,
        expectedSha256: String? = null,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        onProgress: ((received: Long, total: Long?) -> Unit)? = null,
    ): ApkDownload {
        if (!url.startsWith("https://", ignoreCase = true)) return ApkDownload.InvalidUrl

        if (!cacheDir.exists()) cacheDir.mkdirs()
        val target = File(cacheDir, "apk-${UUID.randomUUID()}.download")
        var completed = false

        try {
            val response = try {
                // Bare GET: no Authorization / API headers are attached.
                httpClient.get(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return ApkDownload.Offline
            }

            if (!response.status.isSuccess()) return ApkDownload.HttpError

            val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            val channel = response.bodyAsChannel()
            var received = 0L

            target.outputStream().use { out ->
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    received += read
                    if (received > maxBytes) return ApkDownload.TooLarge
                    onProgress?.invoke(received, total)
                }
            }

            val sha = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            if (expectedSizeBytes != null && received != expectedSizeBytes) {
                return ApkDownload.IntegrityMismatch
            }
            val normalizedExpected = expectedSha256?.removePrefix("sha256:")
            if (normalizedExpected != null && !normalizedExpected.equals(sha, ignoreCase = true)) {
                return ApkDownload.IntegrityMismatch
            }

            completed = true
            return ApkDownload.Completed(target, received, sha)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (!completed) target.delete()
        }
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024
        const val BUFFER_SIZE = 64 * 1024
    }
}
