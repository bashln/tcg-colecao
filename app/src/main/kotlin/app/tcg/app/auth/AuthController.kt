package app.tcg.app.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val sessionLock = Mutex()
    private var sessionVersion = 0L
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Restores a persisted session on cold start, refreshing it when it has expired. */
    suspend fun start() {
        val (version, stored) = sessionLock.withLock { sessionVersion to store.read() }
        if (stored == null) {
            sessionLock.withLock { if (sessionVersion == version) _state.value = AuthState.SignedOut }
            return
        }
        if (!isExpired(stored)) {
            sessionLock.withLock { if (sessionVersion == version) _state.value = AuthState.SignedIn(stored) }
            return
        }
        try {
            val refreshed = remote.refresh(stored.refreshToken)
            sessionLock.withLock {
                if (sessionVersion == version) {
                    check(refreshed.userId == stored.userId)
                    store.write(refreshed)
                    _state.value = AuthState.SignedIn(refreshed)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Only an explicit credential rejection may drop the session. A network or
            // unknown failure keeps it and reports the offline state instead.
            val rejected = (error as? AuthRemoteException)?.error?.rejectedCredential == true
            sessionLock.withLock {
                if (sessionVersion == version) {
                    if (rejected) {
                        store.clear()
                        _state.value = AuthState.SignedOut
                    } else {
                        _state.value = AuthState.SignedIn(stored, offline = true)
                    }
                }
            }
        }
    }

    /**
     * Forces a refresh after a 401 from another component. Returns [SessionRefresh.Rejected]
     * only when the server refused the credential, in which case the session is cleared and
     * the state becomes signed out; an unreachable server leaves the stored session intact.
     */
    suspend fun refreshSession(): SessionRefresh {
        val (version, current) = sessionLock.withLock { sessionVersion to store.read() }
        if (current == null) return SessionRefresh.Rejected
        return try {
            val refreshed = remote.refresh(current.refreshToken)
            sessionLock.withLock {
                if (sessionVersion != version) return@withLock SessionRefresh.Rejected
                if (refreshed.userId != current.userId) {
                    store.clear()
                    _state.value = AuthState.SignedOut
                    SessionRefresh.Rejected
                } else {
                    store.write(refreshed)
                    _state.value = AuthState.SignedIn(refreshed)
                    SessionRefresh.Refreshed(refreshed)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if ((error as? AuthRemoteException)?.error?.rejectedCredential == true) {
                sessionLock.withLock {
                    if (sessionVersion == version) {
                        store.clear()
                        _state.value = AuthState.SignedOut
                    }
                }
                SessionRefresh.Rejected
            } else {
                SessionRefresh.Unavailable
            }
        }
    }

    suspend fun signUp(email: String, password: String): AuthResult = try {
        when (val outcome = remote.signUp(email, password)) {
            is RemoteAuth.Session -> signInState(outcome.session)
            is RemoteAuth.VerificationRequired -> {
                sessionLock.withLock {
                    sessionVersion++
                    store.clear()
                    _state.value = AuthState.AwaitingVerification(outcome.email)
                    AuthResult.Success(_state.value)
                }
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
        val current = sessionLock.withLock {
            sessionVersion++
            val signedIn = _state.value as? AuthState.SignedIn
            store.clear()
            _state.value = AuthState.SignedOut
            signedIn
        } ?: return
        try {
            remote.signOut(current.session.accessToken)
        } catch (_: Exception) {
            // Best-effort: a failed remote logout must not block local sign-out.
        }
    }

    private suspend fun signInState(session: AuthSession): AuthResult = sessionLock.withLock {
        sessionVersion++
        store.write(session)
        _state.value = AuthState.SignedIn(session)
        AuthResult.Success(_state.value)
    }

    private fun isExpired(session: AuthSession): Boolean =
        nowEpochSeconds() >= session.expiresAtEpochSeconds - EXPIRY_MARGIN_SECONDS

    private companion object {
        const val EXPIRY_MARGIN_SECONDS = 60L
    }
}
