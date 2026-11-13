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

    /**
     * [offline] is set when a stored session could not be refreshed because the server was
     * unreachable. The session stays valid locally; only a rejection clears it.
     */
    data class SignedIn(val session: AuthSession, val offline: Boolean = false) : AuthState
}

/** Coarse, non-revealing error taxonomy. Messages never disclose whether an email exists. */
sealed interface AuthError {
    data object InvalidCredentials : AuthError
    data object EmailAlreadyRegistered : AuthError
    data object EmailNotConfirmed : AuthError
    data object Network : AuthError
    data class Unknown(val message: String?) : AuthError
}

/**
 * True only when the server explicitly refused the credential. A network or unknown failure
 * says nothing about the session, so it must never clear it.
 */
val AuthError.rejectedCredential: Boolean
    get() = this == AuthError.InvalidCredentials || this == AuthError.EmailNotConfirmed

sealed interface AuthResult {
    data class Success(val state: AuthState) : AuthResult
    data class Failure(val error: AuthError) : AuthResult
}

/** Outcome of a remote sign-up: either a live session or a pending email verification. */
sealed interface RemoteAuth {
    data class Session(val session: AuthSession) : RemoteAuth
    data class VerificationRequired(val email: String) : RemoteAuth
}

/** Result of a forced session refresh, as seen by a caller recovering from a 401. */
sealed interface SessionRefresh {
    data class Refreshed(val session: AuthSession) : SessionRefresh

    /** The server rejected the credential; the session has already been cleared. */
    data object Rejected : SessionRefresh

    /** The server could not be reached; the stored session is unchanged. */
    data object Unavailable : SessionRefresh
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
