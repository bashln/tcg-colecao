package app.tcg.app.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class ApkDownloaderTest {

    private val payload = ByteArray(2048) { it.toByte() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tempDir(): File = Files.createTempDirectory("apk-test").toFile()

    private fun downloader(engine: MockEngine, dir: File) = ApkDownloader(
        httpClient = HttpClient(engine),
        cacheDir = dir,
    )

    private fun okEngine() = MockEngine {
        respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/octet-stream")))
    }

    private fun File.isEmptyDir(): Boolean = listFiles().isNullOrEmpty()

    @Test
    fun `https asset downloads and reports size and sha256`() = runTest {
        val dir = tempDir()
        val result = downloader(okEngine(), dir).download(
            url = "https://example.com/a.apk",
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256 = sha256Hex(payload),
        )

        val completed = result as ApkDownload.Completed
        assertEquals(payload.size.toLong(), completed.sizeBytes)
        assertEquals(sha256Hex(payload), completed.sha256)
        assertTrue(completed.file.exists())
        assertTrue(completed.file.readBytes().contentEquals(payload))
    }

    @Test
    fun `http url is rejected as invalid`() = runTest {
        val result = downloader(okEngine(), tempDir()).download(url = "http://example.com/a.apk")
        assertEquals(ApkDownload.InvalidUrl, result)
    }

    @Test
    fun `download sends no authorization or api headers`() = runTest {
        lateinit var captured: HttpRequestData
        val engine = MockEngine { request ->
            captured = request
            respond(payload, HttpStatusCode.OK, headersOf())
        }

        downloader(engine, tempDir()).download(url = "https://example.com/a.apk")

        // No secret-bearing header and no GitHub API media type may reach the asset host.
        // (Ktor's generic `Accept: */*` default is harmless and not an API header.)
        assertEquals(null, captured.headers[HttpHeaders.Authorization])
        assertTrue(
            "no GitHub API Accept header may be sent to the asset host",
            captured.headers[HttpHeaders.Accept] != "application/vnd.github+json",
        )
    }

    @Test
    fun `http error status is rejected and leaves no file`() = runTest {
        val dir = tempDir()
        val engine = MockEngine {
            respond("boom", HttpStatusCode.InternalServerError, headersOf())
        }

        val result = downloader(engine, dir).download(url = "https://example.com/a.apk")

        assertEquals(ApkDownload.HttpError, result)
        assertTrue("no file may remain after an error", dir.isEmptyDir())
    }

    @Test
    fun `size mismatch is rejected and removes the partial`() = runTest {
        val dir = tempDir()
        val result = downloader(okEngine(), dir).download(
            url = "https://example.com/a.apk",
            expectedSizeBytes = payload.size.toLong() + 1,
        )

        assertEquals(ApkDownload.IntegrityMismatch, result)
        assertTrue(dir.isEmptyDir())
    }

    @Test
    fun `sha256 mismatch is rejected and removes the partial`() = runTest {
        val dir = tempDir()
        val result = downloader(okEngine(), dir).download(
            url = "https://example.com/a.apk",
            expectedSha256 = "deadbeef",
        )

        assertEquals(ApkDownload.IntegrityMismatch, result)
        assertTrue(dir.isEmptyDir())
    }

    @Test
    fun `oversized body is bounded and removes the partial`() = runTest {
        val dir = tempDir()
        val result = downloader(okEngine(), dir).download(
            url = "https://example.com/a.apk",
            maxBytes = 16L,
        )

        assertEquals(ApkDownload.TooLarge, result)
        assertTrue(dir.isEmptyDir())
    }
}
