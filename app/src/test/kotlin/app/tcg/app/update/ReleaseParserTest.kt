package app.tcg.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {

    private fun asset(
        name: String = "tcg-colecao-v1.2.3.apk",
        contentType: String = "application/vnd.android.package-archive",
        url: String = "https://github.com/o/r/releases/download/v1.2.3/$name",
        size: Long = 1_000L,
        digest: String? = null,
    ): String {
        val digestField = if (digest == null) "" else ""","digest":"$digest""""
        return """{"name":"$name","content_type":"$contentType","browser_download_url":"$url","size":$size$digestField}"""
    }

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        notes: String = "notes",
        assets: List<String> = listOf(asset()),
    ): String = """{"tag_name":"$tag","body":"$notes","draft":$draft,"prerelease":$prerelease,"assets":[${assets.joinToString(",")}]}"""

    private fun page(vararg releases: String): String = "[${releases.joinToString(",")}]"

    @Test
    fun `stable releases are kept and drafts and prereleases are excluded`() {
        val result = ReleaseParser.parse(
            page(
                release("v9.9.9", draft = true),
                release("v8.8.8", prerelease = true),
                release("v1.2.3"),
            ),
        )

        val selected = result as ReleaseSelection.Selected
        assertEquals("v1.2.3", selected.release.tagName)
    }

    @Test
    fun `highest stable version wins when several exist`() {
        val result = ReleaseParser.parse(
            page(release("v1.2.3"), release("v2.0.0"), release("v1.10.0")),
        )

        val selected = result as ReleaseSelection.Selected
        assertEquals("v2.0.0", selected.release.tagName)
    }

    @Test
    fun `versionCode is derived from the tag using major*10000+minor*100+patch`() {
        val result = ReleaseParser.parse(page(release("v1.2.3")))

        val selected = result as ReleaseSelection.Selected
        assertEquals(10_203L, selected.release.versionCode)
        assertEquals("1.2.3", selected.release.versionName)
    }

    @Test
    fun `apk asset is matched by file name and android package content type`() {
        val result = ReleaseParser.parse(
            page(
                release(
                    "v1.2.3",
                    assets = listOf(
                        asset(name = "notes.txt", contentType = "text/plain"),
                        asset(name = "tcg-colecao-v1.2.3.apk", url = "https://example/apk", size = 42L, digest = "sha256:abc123"),
                    ),
                ),
            ),
        )

        val selected = result as ReleaseSelection.Selected
        assertEquals("https://example/apk", selected.release.apkUrl)
        assertEquals(42L, selected.release.apkSizeBytes)
        assertEquals("abc123", selected.release.apkSha256)
    }

    @Test
    fun `strict stable tag format rejects pre-release suffixes`() {
        val result = ReleaseParser.parse(page(release("v1.2.3-beta")))
        assertTrue("expected typed rejection, got $result", result is ReleaseSelection.Rejected)
        assertEquals(OtaError.MalformedTag, (result as ReleaseSelection.Rejected).error)
    }

    @Test
    fun `malformed tag is rejected with a typed error`() {
        val result = ReleaseParser.parse(page(release("release-5")))
        assertTrue(result is ReleaseSelection.Rejected)
        assertEquals(OtaError.MalformedTag, (result as ReleaseSelection.Rejected).error)
    }

    @Test
    fun `missing apk asset is a typed error`() {
        val result = ReleaseParser.parse(page(release("v1.2.3", assets = emptyList())))
        assertTrue(result is ReleaseSelection.Rejected)
        assertEquals(OtaError.MissingApkAsset, (result as ReleaseSelection.Rejected).error)
    }

    @Test
    fun `multiple apk assets are a typed error`() {
        val result = ReleaseParser.parse(
            page(
                release(
                    "v1.2.3",
                    assets = listOf(asset(name = "a.apk"), asset(name = "b.apk")),
                ),
            ),
        )
        assertTrue(result is ReleaseSelection.Rejected)
        assertEquals(OtaError.MultipleApkAssets, (result as ReleaseSelection.Rejected).error)
    }

    @Test
    fun `versionCode overflow is rejected`() {
        val result = ReleaseParser.parse(page(release("v999999999.0.0")))
        assertTrue(result is ReleaseSelection.Rejected)
        assertEquals(OtaError.VersionOverflow, (result as ReleaseSelection.Rejected).error)
    }

    @Test
    fun `patch component above 99 is rejected to prevent versionCode collisions`() {
        val result = ReleaseParser.parse(page(release("v1.0.100")))
        assertTrue(result is ReleaseSelection.Rejected)
        assertEquals(OtaError.MalformedTag, (result as ReleaseSelection.Rejected).error)
    }

    @Test
    fun `minor component above 99 is rejected to prevent versionCode collisions`() {
        val result = ReleaseParser.parse(page(release("v1.100.0")))
        assertTrue(result is ReleaseSelection.Rejected)
        assertEquals(OtaError.MalformedTag, (result as ReleaseSelection.Rejected).error)
    }

    @Test
    fun `invalid json is rejected`() {
        val result = ReleaseParser.parse("not json at all")
        assertTrue(result is ReleaseSelection.Rejected)
        assertEquals(OtaError.InvalidJson, (result as ReleaseSelection.Rejected).error)
    }

    @Test
    fun `no stable release returns no stable`() {
        val result = ReleaseParser.parse(
            page(release("v9.9.9", draft = true), release("v8.8.8", prerelease = true)),
        )
        assertEquals(ReleaseSelection.NoStableRelease, result)
    }

    @Test
    fun `highest valid wins even when a lower release is malformed`() {
        val result = ReleaseParser.parse(
            page(release("v1.0.0", assets = emptyList()), release("v2.0.0")),
        )
        val selected = result as ReleaseSelection.Selected
        assertEquals("v2.0.0", selected.release.tagName)
    }
}
