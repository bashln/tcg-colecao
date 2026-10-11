package app.tcg.app.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Supabase GoTrue REST client. Talks to `/auth/v1` for sign-up, password sign-in, refresh
 * and logout. Error responses are mapped to a coarse, non-revealing taxonomy so the UI never
 * discloses whether an email is registered.
 */
class SupabaseAuthClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val httpClient: HttpClient,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : AuthRemote {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun signUp(email: String, password: String): RemoteAuth {
        val body = JsonObject(
            mapOf(
                "email" to JsonPrimitive(email),
                "password" to JsonPrimitive(password),
            ),
        )
        val response = post("$AUTH_PATH/signup", body.toString())
        val session = parseSessionOrNull(response)
            ?: return RemoteAuth.VerificationRequired(email)
        return RemoteAuth.Session(session)
    }

    override suspend fun signIn(email: String, password: String): AuthSession {
        val body = JsonObject(
            mapOf(
                "email" to JsonPrimitive(email),
                "password" to JsonPrimitive(password),
            ),
        )
        val response = post("$AUTH_PATH/token?grant_type=password", body.toString())
        return parseSessionOrNull(response)
            ?: throw AuthRemoteException(AuthError.InvalidCredentials, "Resposta de login inválida")
    }

    override suspend fun refresh(refreshToken: String): AuthSession {
        val body = JsonObject(mapOf("refresh_token" to JsonPrimitive(refreshToken)))
        val response = post("$AUTH_PATH/token?grant_type=refresh_token", body.toString())
        return parseSessionOrNull(response)
            ?: throw AuthRemoteException(AuthError.InvalidCredentials, "Resposta de renovação inválida")
    }

    override suspend fun signOut(accessToken: String) {
        // Best-effort: a failed or already-invalid logout must not block local sign-out.
        try {
            httpClient.post("$baseUrl$AUTH_PATH/logout") {
                contentType(ContentType.Application.Json)
                header("apikey", apiKey)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
        } catch (_: CancellationException) {
            throw CancellationException("logout cancelado")
        } catch (_: Exception) {
            // Ignore network failures on logout.
        }
    }

    private suspend fun post(path: String, body: String): JsonObject {
        val response = try {
            httpClient.post("$baseUrl$path") {
                contentType(ContentType.Application.Json)
                header("apikey", apiKey)
                setBody(body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw AuthRemoteException(AuthError.Network, error.message ?: "Sem conexão")
        }

        val text = try {
            response.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw AuthRemoteException(AuthError.Unknown(null), "Resposta inválida")
        }

        val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

        if (!response.status.isSuccess()) {
            throw AuthRemoteException(mapError(response.status, parsed), text.ifBlank { "Falha de autenticação" })
        }
        return parsed ?: throw AuthRemoteException(AuthError.Unknown(null), "Resposta inválida")
    }

    private fun mapError(status: HttpStatusCode, body: JsonObject?): AuthError {
        val code = body?.string("code")
        return when (code) {
            "invalid_credentials" -> AuthError.InvalidCredentials
            "user_already_exists" -> AuthError.EmailAlreadyRegistered
            "email_not_confirmed" -> AuthError.EmailNotConfirmed
            else -> when (status) {
                HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized -> AuthError.InvalidCredentials
                else -> AuthError.Unknown(body?.string("msg"))
            }
        }
    }

    private fun parseSessionOrNull(body: JsonObject): AuthSession? {
        val accessToken = body.string("access_token") ?: return null
        val refreshToken = body.string("refresh_token") ?: return null
        val user = body["user"] as? JsonObject
        val expiresAt = body.longOrNull("expires_at")
            ?: body.longOrNull("expires_in")?.let { nowEpochSeconds() + it }
            ?: (nowEpochSeconds() + DEFAULT_EXPIRES_IN)
        return AuthSession(
            userId = user?.string("id") ?: "",
            email = user?.string("email") ?: "",
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSeconds = expiresAt,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.contentOrNull

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.longOrNull

    private companion object {
        const val AUTH_PATH = "/auth/v1"
        const val DEFAULT_EXPIRES_IN = 3600L
    }
}
