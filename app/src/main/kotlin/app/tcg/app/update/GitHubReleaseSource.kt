package app.tcg.app.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Fetches the public GitHub `/releases` page for the app's own repository. Anonymous only —
 * no token is ever sent (the repo and its releases are public). An `ETag` is kept in memory
 * together with the body it came from, and a conditional `If-None-Match` is sent only when a
 * body is already cached, so a `304` can always hand back the cached payload instead of
 * leaving the caller with nothing.
 */
class GitHubReleaseSource(
    private val httpClient: HttpClient,
    private val repoSlug: String = "bashln/tcg-colecao",
    private val apiBase: String = "https://api.github.com",
    private val userAgent: String = "tcg-colecao-android",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var lastEtag: String? = null
    private var lastBody: String? = null

    suspend fun fetchLatestStable(): ReleaseFetch {
        // Only send a conditional header when we already hold the matching body.
        val ifNoneMatch = synchronized(lock) { if (lastBody != null) lastEtag else null }

        val response = try {
            httpClient.get("$apiBase/repos/$repoSlug/releases?per_page=$PAGE_SIZE") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, userAgent)
                ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ReleaseFetch.Offline
        }

        return when {
            response.status == HttpStatusCode.NotModified -> {
                val cached = synchronized(lock) { lastBody }
                    ?: return ReleaseFetch.Offline
                ReleaseFetch.NotModified(cached)
            }

            response.status == HttpStatusCode.TooManyRequests || isRateLimited(response) ->
                ReleaseFetch.RateLimited(response.headerLong(RATE_LIMIT_RESET))

            response.status == HttpStatusCode.OK -> {
                val body = response.bodyAsText()
                val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
                if (element !is JsonArray) return ReleaseFetch.Invalid(OtaError.InvalidJson)
                val etag = response.headers[HttpHeaders.ETag]
                synchronized(lock) {
                    lastEtag = etag
                    lastBody = body
                }
                ReleaseFetch.Fetched(etag, body)
            }

            else -> ReleaseFetch.Invalid(OtaError.InvalidJson)
        }
    }

    private fun isRateLimited(response: HttpResponse): Boolean =
        response.status == HttpStatusCode.Forbidden && response.headers[RATE_LIMIT_REMAINING] == "0"

    private fun HttpResponse.headerLong(name: String): Long? = headers[name]?.toLongOrNull()

    private companion object {
        const val PAGE_SIZE = 20
        const val RATE_LIMIT_RESET = "X-RateLimit-Reset"
        const val RATE_LIMIT_REMAINING = "X-RateLimit-Remaining"
    }
}
