package app.tcg.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UpdateInstallerTest {

    private lateinit var context: Context
    private lateinit var launched: MutableList<Intent>

    private fun installer(
        canRequest: Boolean = true,
        archivePackage: (File) -> String? = { context.packageName },
    ) = UpdateInstaller(
        context = context,
        canRequestInstalls = { canRequest },
        archivePackageName = archivePackage,
        launch = { launched += it },
        // Injected so the test exercises the installer's intent construction without depending
        // on Robolectric's FileProvider XML parsing; production defaults to the real FileProvider.
        contentUri = { Uri.parse("content://${context.packageName}.fileprovider/${it.name}") },
    )

    private fun cacheApk(): File =
        File(context.cacheDir, "update.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        launched = mutableListOf()
    }

    @Test
    fun `validated in-cache apk is handed to the os installer`() {
        val result = installer().handoff(cacheApk())

        assertEquals(UpdateHandoff.HandedOff, result)
        val intent = launched.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${context.packageName}.fileprovider", intent.data?.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `missing install-source permission routes to settings`() {
        val result = installer(canRequest = false).handoff(cacheApk())

        assertEquals(UpdateHandoff.NeedsInstallPermission, result)
        assertTrue("the OS installer must not launch without permission", launched.isEmpty())

        val settings = installer(canRequest = false).installSettingsIntent()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.action)
        assertEquals(Uri.parse("package:${context.packageName}"), settings.data)
    }

    @Test
    fun `non-existent apk is refused`() {
        val missing = File(context.cacheDir, "missing.apk")
        assertEquals(UpdateHandoff.InvalidApk, installer().handoff(missing))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `file outside the private cache is refused`() {
        val outside = File(context.filesDir, "outside.apk").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(UpdateHandoff.InvalidApk, installer().handoff(outside))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `unparseable or wrong-package apk is refused`() {
        assertEquals(UpdateHandoff.InvalidApk, installer(archivePackage = { null }).handoff(cacheApk()))
        assertEquals(UpdateHandoff.InvalidApk, installer(archivePackage = { "com.other.app" }).handoff(cacheApk()))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `handoff grants only read permission via a content uri`() {
        installer().handoff(cacheApk())

        val intent = launched.single()
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse("no write grant may be exposed", intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        // A user-facing OS dialog (ACTION_VIEW), never a silent PackageInstaller session.
        assertEquals(Intent.ACTION_VIEW, intent.action)
    }

    @Test
    fun `the file provider authority is app-scoped and not exported`() {
        val info = context.packageManager.resolveContentProvider("${context.packageName}.fileprovider", 0)
        assertNotNull("FileProvider must be declared", info)
        assertFalse("provider must not be exported", info!!.exported)
        assertTrue("URI permissions are granted per intent", info.grantUriPermissions)
    }
}
