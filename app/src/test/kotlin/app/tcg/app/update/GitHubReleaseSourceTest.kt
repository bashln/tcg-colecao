package app.tcg.app.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseSourceTest {

    private val releasesBody =
        """[{"tag_name":"v1.2.3","body":"n","draft":false,"prerelease":false,"assets":[{"name":"a.apk","content_type":"application/vnd.android.package-archive","browser_download_url":"https://x/a.apk","size":1}]}]"""

    private fun source(engine: MockEngine) = GitHubReleaseSource(
        httpClient = HttpClient(engine),
        repoSlug = "bashln/tcg-colecao",
    )

    private fun jsonHeaders(vararg pairs: Pair<String, String>): Headers {
        val all = buildList {
            add(HttpHeaders.ContentType to listOf("application/json"))
            pairs.forEach { (name, value) -> add(name to listOf(value)) }
        }
        return headersOf(*all.toTypedArray())
    }

    @Test
    fun `fetch hits the public releases endpoint with accept and user agent and no token`() = runTest {
        lateinit var captured: HttpRequestData
        val engine = MockEngine { request ->
            captured = request
            respond(releasesBody, HttpStatusCode.OK, jsonHeaders(HttpHeaders.ETag to "W/\"abc\""))
        }

        val result = source(engine).fetchLatestStable()

        assertTrue(result is ReleaseFetch.Fetched)
        assertTrue(captured.url.toString().contains("api.github.com/repos/bashln/tcg-colecao/releases"))
        assertEquals("application/vnd.github+json", captured.headers[HttpHeaders.Accept])
        assertNotNull("a User-Agent must be sent", captured.headers[HttpHeaders.UserAgent])
        assertNull("no token may ever be sent", captured.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `fetch maps a 200 body to Fetched with the etag`() = runTest {
        val engine = MockEngine {
            respond(releasesBody, HttpStatusCode.OK, jsonHeaders(HttpHeaders.ETag to "W/\"abc\""))
        }

        val result = source(engine).fetchLatestStable()

        val fetched = result as ReleaseFetch.Fetched
        assertEquals(releasesBody, fetched.releasesJson)
        assertEquals("W/\"abc\"", fetched.etag)
    }

    @Test
    fun `malformed json maps to a typed failure`() = runTest {
        val engine = MockEngine {
            respond("not json at all", HttpStatusCode.OK, jsonHeaders())
        }

        val result = source(engine).fetchLatestStable()

        assertTrue(result is ReleaseFetch.Invalid)
        assertEquals(OtaError.InvalidJson, (result as ReleaseFetch.Invalid).error)
    }

    @Test
    fun `offline network failure maps to Offline`() = runTest {
        val engine = MockEngine { error("no route to host") }

        val result = source(engine).fetchLatestStable()

        assertEquals(ReleaseFetch.Offline, result)
    }

    @Test
    fun `rate limit 429 with reset header maps to RateLimited`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"message":"rate limited"}""",
                status = HttpStatusCode.TooManyRequests,
                headers = jsonHeaders("X-RateLimit-Reset" to "1791667200"),
            )
        }

        val result = source(engine).fetchLatestStable()

        val limited = result as ReleaseFetch.RateLimited
        assertEquals(1_791_667_200L, limited.resetEpochSeconds)
    }

    @Test
    fun `a rate limited api falls back to the public latest release redirect`() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.host == "api.github.com" -> respond(
                    content = "",
                    status = HttpStatusCode.TooManyRequests,
                    headers = jsonHeaders("X-RateLimit-Reset" to "1791667200"),
                )
                request.method == HttpMethod.Head -> respond(
                    content = "",
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentLength to listOf("58352522"),
                        HttpHeaders.ContentType to listOf("application/vnd.android.package-archive"),
                    ),
                )
                request.url.encodedPath.startsWith("/bashln/tcg-colecao/releases/tag/") -> respond(
                    content = "<html></html>",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType to listOf("text/html")),
                )
                else -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(
                        HttpHeaders.Location to listOf("https://github.com/bashln/tcg-colecao/releases/tag/v0.7.6"),
                    ),
                )
            }
        }

        val result = source(engine).fetchLatestStable()

        val release = (result as ReleaseFetch.FetchedRelease).release
        assertEquals("v0.7.6", release.tagName)
        assertEquals("0.7.6", release.versionName)
        assertEquals(706L, release.versionCode)
        assertEquals(
            "https://github.com/bashln/tcg-colecao/releases/download/v0.7.6/tcg-colecao-v0.7.6.apk",
            release.apkUrl,
        )
        assertEquals(58_352_522L, release.apkSizeBytes)
    }

    @Test
    fun `a rate limited api with an unusable redirect stays rate limited`() = runTest {
        val engine = MockEngine { request ->
            if (request.url.host == "api.github.com") {
                respond("", HttpStatusCode.TooManyRequests, jsonHeaders("X-RateLimit-Reset" to "1791667200"))
            } else {
                respond("", HttpStatusCode.NotFound, headersOf())
            }
        }

        val result = source(engine).fetchLatestStable()

        assertEquals(ReleaseFetch.RateLimited(1_791_667_200L), result)
    }

    @Test
    fun `etag 304 returns NotModified carrying the cached body`() = runTest {
        val engine = MockEngine { request ->
            if (request.headers.contains(HttpHeaders.IfNoneMatch)) {
                respond("", HttpStatusCode.NotModified, headersOf())
            } else {
                respond(releasesBody, HttpStatusCode.OK, jsonHeaders(HttpHeaders.ETag to "W/\"abc\""))
            }
        }
        val client = source(engine)

        val first = client.fetchLatestStable()
        assertTrue(first is ReleaseFetch.Fetched)

        val second = client.fetchLatestStable()

        val notModified = second as ReleaseFetch.NotModified
        assertEquals("the cached body must ride along so a 304 never strands the caller", releasesBody, notModified.cachedReleasesJson)
    }

    @Test
    fun `no conditional header is sent before a body has been cached`() = runTest {
        lateinit var captured: HttpRequestData
        val engine = MockEngine { request ->
            captured = request
            respond(releasesBody, HttpStatusCode.OK, jsonHeaders(HttpHeaders.ETag to "W/\"abc\""))
        }

        source(engine).fetchLatestStable()

        // An ETag must never be sent without an associated cached body.
        assertNull(captured.headers[HttpHeaders.IfNoneMatch])
    }
}
