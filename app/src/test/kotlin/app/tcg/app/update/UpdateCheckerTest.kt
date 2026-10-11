package app.tcg.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private fun release(versionCode: Long, versionName: String) = GitHubRelease(
        tagName = "v$versionName",
        versionName = versionName,
        versionCode = versionCode,
        releaseNotes = "notes",
        apkUrl = "https://example/apk.apk",
        apkSizeBytes = 1L,
        apkSha256 = null,
        publishedAt = null,
    )

    @Test
    fun `newer remote version offers an update`() {
        val result = UpdateChecker.check(currentVersionCode = 10_203L, remote = release(10_300L, "1.3.0"))
        assertEquals(UpdateCheck.Available(release(10_300L, "1.3.0")), result)
    }

    @Test
    fun `equal version offers no update`() {
        val result = UpdateChecker.check(currentVersionCode = 10_203L, remote = release(10_203L, "1.2.3"))
        assertEquals(UpdateCheck.NoUpdate, result)
    }

    @Test
    fun `older remote version offers no update (never downgrade)`() {
        val result = UpdateChecker.check(currentVersionCode = 10_300L, remote = release(10_203L, "1.2.3"))
        assertEquals(UpdateCheck.NoUpdate, result)
    }

    @Test
    fun `version name mismatch with higher code still offers update`() {
        // Decision is driven purely by versionCode, not the display name.
        val result = UpdateChecker.check(currentVersionCode = 1L, remote = release(10_000L, "1.0.0"))
        assertTrue(result is UpdateCheck.Available)
    }

    @Test
    fun `null remote offers no update`() {
        assertEquals(UpdateCheck.NoUpdate, UpdateChecker.check(currentVersionCode = 1L, remote = null))
    }
}
