package app.tcg.app.ui.update

import app.tcg.app.testutil.MainDispatcherRule
import app.tcg.app.update.ApkDownload
import app.tcg.app.update.ApkMetadata
import app.tcg.app.update.GitHubRelease
import app.tcg.app.update.InstalledAppInfo
import app.tcg.app.update.ReleaseFetch
import app.tcg.app.update.UpdateCoordinator
import app.tcg.app.update.UpdateHandoff
import app.tcg.app.update.UpdateState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val installed = InstalledAppInfo("app.tcg.app", 10_203L, "aa:bb")
    private val apkFile = File("update.apk")

    private lateinit var handoffs: MutableList<File>
    private var settingsOpens = 0
    private var handoffResult: UpdateHandoff = UpdateHandoff.HandedOff

    private fun releases(tag: String) =
        """[{"tag_name":"$tag","body":"notes","draft":false,"prerelease":false,"assets":[{"name":"a.apk","content_type":"application/vnd.android.package-archive","browser_download_url":"https://x/a.apk","size":100}]}]"""

    private fun vm(
        fetch: suspend () -> ReleaseFetch = { ReleaseFetch.Fetched("e", releases("v2.0.0")) },
        download: suspend (GitHubRelease, ((Long, Long?) -> Unit)?) -> ApkDownload = { _, progress ->
            progress?.invoke(100L, 100L)
            ApkDownload.Completed(apkFile, 100L, "sha")
        },
        metadata: suspend (File) -> ApkMetadata? = { ApkMetadata("app.tcg.app", 20_000L, "aa:bb") },
    ): UpdateViewModel {
        val coordinator = UpdateCoordinator(installed, fetch, download, metadata)
        return UpdateViewModel(
            coordinator = coordinator,
            installHandoff = { file -> handoffs += file; handoffResult },
            openInstallSettings = { settingsOpens += 1 },
        )
    }

    @Before
    fun setUp() {
        handoffs = mutableListOf()
        settingsOpens = 0
        handoffResult = UpdateHandoff.HandedOff
    }

    @Test
    fun `check reports no update when none is newer`() = runTest {
        val vm = vm(fetch = { ReleaseFetch.Fetched("e", releases("v1.0.0")) })
        vm.check()
        advanceUntilIdle()
        assertEquals(UpdateState.NoUpdate, vm.state.value)
    }

    @Test
    fun `available release does not download or install until accepted`() = runTest {
        val vm = vm()
        vm.check()
        advanceUntilIdle()
        assertTrue(vm.state.value is UpdateState.Ready)

        // Tapping install before accepting must be a no-op; nothing downloads or installs.
        vm.install()
        advanceUntilIdle()

        assertTrue(vm.state.value is UpdateState.Ready)
        assertTrue(handoffs.isEmpty())
    }

    @Test
    fun `accepting the update downloads to install-ready`() = runTest {
        val vm = vm()
        vm.check()
        advanceUntilIdle()

        vm.acceptAndDownload()
        advanceUntilIdle()

        assertTrue(vm.state.value is UpdateState.InstallReady)
    }

    @Test
    fun `cancel leaves no install`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = vm(download = { _, _ -> gate.await(); ApkDownload.Completed(apkFile, 100L, "sha") })
        vm.check()
        advanceUntilIdle()

        vm.acceptAndDownload()
        runCurrent()
        assertTrue(vm.state.value is UpdateState.Downloading)

        vm.cancelDownload()
        advanceUntilIdle()

        assertTrue("cancellation must return to ready", vm.state.value is UpdateState.Ready)
        assertFalse(vm.state.value is UpdateState.InstallReady)
        assertTrue(handoffs.isEmpty())
    }

    @Test
    fun `install calls handoff only and never auto-installs`() = runTest {
        val vm = vm()
        vm.check()
        advanceUntilIdle()
        vm.acceptAndDownload()
        advanceUntilIdle()
        assertTrue(vm.state.value is UpdateState.InstallReady)
        assertTrue("no handoff before the user taps install", handoffs.isEmpty())

        vm.install()
        advanceUntilIdle()

        assertEquals(listOf(apkFile), handoffs)
        assertEquals("install must not open settings on success", 0, settingsOpens)
        assertTrue(vm.state.value is UpdateState.InstallReady)
    }

    @Test
    fun `install routes to settings when source permission is missing`() = runTest {
        handoffResult = UpdateHandoff.NeedsInstallPermission
        val vm = vm()
        vm.check()
        advanceUntilIdle()
        vm.acceptAndDownload()
        advanceUntilIdle()

        vm.install()
        advanceUntilIdle()

        assertEquals(1, settingsOpens)
    }

    @Test
    fun `install is blocked when the apk is invalid`() = runTest {
        handoffResult = UpdateHandoff.InvalidApk
        val vm = vm()
        vm.check()
        advanceUntilIdle()
        vm.acceptAndDownload()
        advanceUntilIdle()

        vm.install()
        advanceUntilIdle()

        assertTrue(vm.installBlocked.value)
        assertEquals(0, settingsOpens)
    }

    @Test
    fun `offline check surfaces offline error`() = runTest {
        val vm = vm(fetch = { ReleaseFetch.Offline })
        vm.check()
        advanceUntilIdle()
        assertEquals(UpdateState.Error(app.tcg.app.update.UpdateErrorKind.Offline), vm.state.value)
    }

    @Test
    fun `dismiss clears a blocked install`() = runTest {
        handoffResult = UpdateHandoff.InvalidApk
        val vm = vm()
        vm.check()
        advanceUntilIdle()
        vm.acceptAndDownload()
        advanceUntilIdle()
        vm.install()
        advanceUntilIdle()
        assertTrue(vm.installBlocked.value)

        vm.dismissError()
        advanceUntilIdle()
        assertFalse(vm.installBlocked.value)
    }
}
