package app.tcg.app.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCoordinatorTest {

    private val installed = InstalledAppInfo("app.tcg.app", 10_203L, "aa:bb")
    private val apkFile = File("apk-test.apk")

    private fun releases(tag: String, notes: String = "notes") =
        """[{"tag_name":"$tag","body":"$notes","draft":false,"prerelease":false,"assets":[{"name":"a.apk","content_type":"application/vnd.android.package-archive","browser_download_url":"https://x/a.apk","size":100}]}]"""

    private fun coordinator(
        fetch: suspend () -> ReleaseFetch = { ReleaseFetch.Fetched("e", releases("v2.0.0")) },
        download: suspend (GitHubRelease, ((Long, Long?) -> Unit)?) -> ApkDownload = { _, progress ->
            progress?.invoke(100L, 100L)
            ApkDownload.Completed(apkFile, 100L, "sha")
        },
        metadata: suspend (File) -> ApkMetadata? = { ApkMetadata("app.tcg.app", 20_000L, "aa:bb") },
    ) = UpdateCoordinator(installed, fetch, download, metadata)

    @Test
    fun `checking with no newer release reports no update`() = runTest {
        val c = coordinator(fetch = { ReleaseFetch.Fetched("e", releases("v1.0.0")) })
        c.check()
        assertEquals(UpdateState.NoUpdate, c.state.value)
    }

    @Test
    fun `newer release becomes ready with notes`() = runTest {
        val c = coordinator(fetch = { ReleaseFetch.Fetched("e", releases("v2.0.0", notes = "great")) })
        c.check()
        val ready = c.state.value as UpdateState.Ready
        assertEquals("v2.0.0", ready.release.tagName)
        assertEquals("great", ready.release.releaseNotes)
    }

    @Test
    fun `offline check surfaces offline and keeps the app usable`() = runTest {
        val c = coordinator(fetch = { ReleaseFetch.Offline })
        c.check()
        assertEquals(UpdateState.Error(UpdateErrorKind.Offline), c.state.value)
    }

    @Test
    fun `rate limited check surfaces rate limited`() = runTest {
        val c = coordinator(fetch = { ReleaseFetch.RateLimited(1_000L) })
        c.check()
        assertEquals(UpdateState.Error(UpdateErrorKind.RateLimited), c.state.value)
    }

    @Test
    fun `download reports progress then becomes install-ready`() = runTest {
        val c = coordinator(
            download = { _, progress ->
                progress?.invoke(50L, 100L)
                yield()
                progress?.invoke(100L, 100L)
                yield()
                ApkDownload.Completed(apkFile, 100L, "sha")
            },
        )
        c.check()
        val seen = mutableListOf<UpdateState>()
        backgroundScope.launch { c.state.collect { seen += it } }
        runCurrent()

        c.download()
        advanceUntilIdle()

        assertTrue(seen.any { it is UpdateState.Downloading && it.received == 50L })
        assertTrue(c.state.value is UpdateState.InstallReady)
    }

    @Test
    fun `install hand-off is user initiated and the coordinator never installs`() = runTest {
        val c = coordinator()
        c.check()
        c.download()
        val installReady = c.state.value as UpdateState.InstallReady
        assertEquals(apkFile, installReady.apkFile)
    }

    @Test
    fun `incompatible package is rejected before the installer and file removed`() = runTest {
        val temp = File.createTempFile("apk", ".apk")
        temp.writeBytes(byteArrayOf(1, 2, 3))
        val c = coordinator(
            download = { _, _ -> ApkDownload.Completed(temp, 3L, "sha") },
            metadata = { ApkMetadata("com.other.app", 20_000L, "aa:bb") },
        )
        c.check()

        c.download()

        val incompatible = c.state.value as UpdateState.Incompatible
        assertEquals(ApkRejectReason.WrongApplicationId, incompatible.reason)
        assertFalse("the incompatible file must be removed", temp.exists())
    }

    @Test
    fun `download failure surfaces download-failed error`() = runTest {
        val c = coordinator(download = { _, _ -> ApkDownload.IntegrityMismatch })
        c.check()
        c.download()
        assertEquals(UpdateState.Error(UpdateErrorKind.DownloadFailed), c.state.value)
    }

    @Test
    fun `cancelled download returns to ready and propagates, never success`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val c = coordinator(
            download = { _, _ ->
                gate.await()
                ApkDownload.Completed(apkFile, 100L, "sha")
            },
        )
        c.check()
        val readyRelease = (c.state.value as UpdateState.Ready).release
        val job = launch { c.download() }
        runCurrent()
        assertTrue(c.state.value is UpdateState.Downloading)

        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertEquals("cancellation must return to ready, never install", UpdateState.Ready(readyRelease), c.state.value)
        assertFalse(c.state.value is UpdateState.InstallReady)
    }

    @Test
    fun `concurrent download is disabled while one is in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val c = coordinator(
            download = { _, _ ->
                calls += 1
                gate.await()
                ApkDownload.Completed(apkFile, 100L, "sha")
            },
        )
        c.check()
        val job = launch { c.download() }
        runCurrent()

        c.download() // second tap while in flight must be a no-op
        runCurrent()
        assertEquals("only one download may run", 1, calls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(c.state.value is UpdateState.InstallReady)
    }

    @Test
    fun `concurrent check is disabled while one is in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val c = coordinator(
            fetch = {
                calls += 1
                gate.await()
                ReleaseFetch.Fetched("e", releases("v2.0.0"))
            },
        )
        val job = launch { c.check() }
        runCurrent()

        c.check()
        runCurrent()
        assertEquals("only one check may run", 1, calls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(c.state.value is UpdateState.Ready)
        assertTrue(job.isActive || job.isCompleted)
    }
}
