package app.tcg.app.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SupabaseAuthClientTest {

    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun auth(engine: MockEngine) = SupabaseAuthClient(
        baseUrl = "https://example.supabase.co",
        apiKey = "publishable-key",
        httpClient = client(engine),
        nowEpochSeconds = { 1_000L },
    )

    private fun bodyText(request: HttpRequestData): String = when (val content = request.body) {
        is TextContent -> content.text
        else -> error("unexpected body ${content::class}")
    }

    @Test
    fun `sign in posts password grant and parses the session`() = runTest {
        lateinit var url: String
        lateinit var body: String
        lateinit var apikey: String
        val engine = MockEngine { request ->
            url = request.url.toString()
            body = bodyText(request)
            apikey = request.headers["apikey"].orEmpty()
            respond(
                content = """{"access_token":"at-1","refresh_token":"rt-1","token_type":"bearer","expires_in":3600,"expires_at":4600,"user":{"id":"user-1","email":"a@example.com"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val session = auth(engine).signIn("a@example.com", "pw")

        assertTrue(url.endsWith("/auth/v1/token?grant_type=password"))
        assertTrue(body.contains("\"email\":\"a@example.com\""))
        assertEquals("publishable-key", apikey)
        assertEquals("at-1", session.accessToken)
        assertEquals("rt-1", session.refreshToken)
        assertEquals(4_600L, session.expiresAtEpochSeconds)
        assertEquals("user-1", session.userId)
        assertEquals("a@example.com", session.email)
    }

    @Test
    fun `sign in maps invalid credentials to a typed error`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"code":"invalid_credentials","msg":"Invalid login credentials"}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        try {
            auth(engine).signIn("a@example.com", "wrong")
            fail("expected invalid credentials")
        } catch (error: AuthRemoteException) {
            assertEquals(AuthError.InvalidCredentials, error.error)
        }
    }

    @Test
    fun `sign up with a live session parses it`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"access_token":"at-2","refresh_token":"rt-2","expires_at":5000,"user":{"id":"user-2","email":"b@example.com"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = auth(engine).signUp("b@example.com", "pw")

        assertTrue(result is RemoteAuth.Session)
        assertEquals("at-2", (result as RemoteAuth.Session).session.accessToken)
    }

    @Test
    fun `sign up without a token requires email verification`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"id":"user-3","aud":"authenticated","role":"authenticated","email":"c@example.com"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = auth(engine).signUp("c@example.com", "pw")

        assertTrue(result is RemoteAuth.VerificationRequired)
        assertEquals("c@example.com", (result as RemoteAuth.VerificationRequired).email)
    }

    @Test
    fun `sign up maps already-registered email to a typed error`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"code":"user_already_exists","msg":"User already registered"}""",
                status = HttpStatusCode.UnprocessableEntity,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        try {
            auth(engine).signUp("b@example.com", "pw")
            fail("expected already registered")
        } catch (error: AuthRemoteException) {
            assertEquals(AuthError.EmailAlreadyRegistered, error.error)
        }
    }

    @Test
    fun `refresh posts the refresh token grant`() = runTest {
        lateinit var url: String
        lateinit var body: String
        val engine = MockEngine { request ->
            url = request.url.toString()
            body = bodyText(request)
            respond(
                content = """{"access_token":"at-9","refresh_token":"rt-9","expires_at":6000,"user":{"id":"user-1","email":"a@example.com"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val session = auth(engine).refresh("rt-1")

        assertTrue(url.endsWith("/auth/v1/token?grant_type=refresh_token"))
        assertTrue(body.contains("\"refresh_token\":\"rt-1\""))
        assertEquals("at-9", session.accessToken)
    }

    @Test
    fun `sign out posts logout with the bearer token and swallows failures`() = runTest {
        var authHeader: String? = null
        val engine = MockEngine { request ->
            authHeader = request.headers[HttpHeaders.Authorization]
            respond(
                content = "",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        // Must not throw even though the server answers 401.
        auth(engine).signOut("at-1")

        assertEquals("Bearer at-1", authHeader)
    }

    @Test
    fun `network failure maps to a typed network error`() = runTest {
        val engine = MockEngine { error("no route to host") }
        try {
            auth(engine).signIn("a@example.com", "pw")
            fail("expected network error")
        } catch (error: AuthRemoteException) {
            assertEquals(AuthError.Network, error.error)
        }
    }
}
