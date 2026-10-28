package app.tcg.app.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Auth state machine. It owns only the session (via [AuthRemote] + [SessionStore]) and has
 * no reference to the collection, which structurally guarantees sign-out can never delete
 * or touch local data. Every state keeps the local-first collection available.
 */
class AuthController(
    private val remote: AuthRemote,
    private val store: SessionStore,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Restores a persisted session on cold start, refreshing it when it has expired. */
    suspend fun start() {
        val stored = store.read()
        if (stored == null) {
            _state.value = AuthState.SignedOut
            return
        }
        if (!isExpired(stored)) {
            _state.value = AuthState.SignedIn(stored)
            return
        }
        try {
            val refreshed = remote.refresh(stored.refreshToken)
            store.write(refreshed)
            _state.value = AuthState.SignedIn(refreshed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            store.clear()
            _state.value = AuthState.SignedOut
        }
    }

    suspend fun signUp(email: String, password: String): AuthResult = try {
        when (val outcome = remote.signUp(email, password)) {
            is RemoteAuth.Session -> signInState(outcome.session)
            is RemoteAuth.VerificationRequired -> {
                _state.value = AuthState.AwaitingVerification(outcome.email)
                AuthResult.Success(_state.value)
            }
        }
    } catch (error: AuthRemoteException) {
        AuthResult.Failure(error.error)
    }

    suspend fun signIn(email: String, password: String): AuthResult = try {
        signInState(remote.signIn(email, password))
    } catch (error: AuthRemoteException) {
        AuthResult.Failure(error.error)
    }

    suspend fun signOut() {
        val current = _state.value as? AuthState.SignedIn ?: return
        try {
            remote.signOut(current.session.accessToken)
        } catch (_: Exception) {
            // Best-effort: a failed remote logout must not block local sign-out.
        }
        store.clear()
        _state.value = AuthState.SignedOut
    }

    private suspend fun signInState(session: AuthSession): AuthResult {
        store.write(session)
        _state.value = AuthState.SignedIn(session)
        return AuthResult.Success(_state.value)
    }

    private fun isExpired(session: AuthSession): Boolean =
        nowEpochSeconds() >= session.expiresAtEpochSeconds - EXPIRY_MARGIN_SECONDS

    private companion object {
        const val EXPIRY_MARGIN_SECONDS = 60L
    }
}
