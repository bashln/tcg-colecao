package app.tcg.app.auth

import kotlinx.serialization.Serializable

/**
 * An authenticated Supabase session. Tokens are held in memory/secure storage only and
 * never written to logs. [expiresAtEpochSeconds] drives proactive refresh.
 */
@Serializable
data class AuthSession(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
)

/** UI-facing auth state. Local collection stays available in every state. */
sealed interface AuthState {
    data object SignedOut : AuthState
    data class AwaitingVerification(val email: String) : AuthState
    data class SignedIn(val session: AuthSession) : AuthState
}

/** Coarse, non-revealing error taxonomy. Messages never disclose whether an email exists. */
sealed interface AuthError {
    data object InvalidCredentials : AuthError
    data object EmailAlreadyRegistered : AuthError
    data object EmailNotConfirmed : AuthError
    data object Network : AuthError
    data class Unknown(val message: String?) : AuthError
}

sealed interface AuthResult {
    data class Success(val state: AuthState) : AuthResult
    data class Failure(val error: AuthError) : AuthResult
}

/** Outcome of a remote sign-up: either a live session or a pending email verification. */
sealed interface RemoteAuth {
    data class Session(val session: AuthSession) : RemoteAuth
    data class VerificationRequired(val email: String) : RemoteAuth
}

class AuthRemoteException(
    val error: AuthError,
    message: String,
) : Exception(message)

/** Narrow remote boundary the controller depends on; production uses [SupabaseAuthClient]. */
interface AuthRemote {
    suspend fun signUp(email: String, password: String): RemoteAuth
    suspend fun signIn(email: String, password: String): AuthSession
    suspend fun refresh(refreshToken: String): AuthSession
    suspend fun signOut(accessToken: String)
}

/** Secure persistence boundary for the session. Production uses platform-protected storage. */
interface SessionStore {
    suspend fun read(): AuthSession?
    suspend fun write(session: AuthSession)
    suspend fun clear()
}
