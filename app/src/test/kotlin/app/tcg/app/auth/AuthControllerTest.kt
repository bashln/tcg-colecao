package app.tcg.app.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthControllerTest {

    private class FakeRemote : AuthRemote {
        var signInResult: AuthSession? = null
        var signInError: AuthError? = null
        var signUpResult: RemoteAuth? = null
        var signUpError: AuthError? = null
        var refreshResult: AuthSession? = null
        var refreshError: AuthError? = null
        var signOutThrows = false
        val signedOutTokens = mutableListOf<String>()
        var refreshCalls = 0

        override suspend fun signUp(email: String, password: String): RemoteAuth {
            signUpError?.let { throw AuthRemoteException(it, "signup failed") }
            return checkNotNull(signUpResult)
        }

        override suspend fun signIn(email: String, password: String): AuthSession {
            signInError?.let { throw AuthRemoteException(it, "signin failed") }
            return checkNotNull(signInResult)
        }

        override suspend fun refresh(refreshToken: String): AuthSession {
            refreshCalls += 1
            refreshError?.let { throw AuthRemoteException(it, "refresh failed") }
            return checkNotNull(refreshResult)
        }

        override suspend fun signOut(accessToken: String) {
            if (signOutThrows) throw AuthRemoteException(AuthError.Network, "offline")
            signedOutTokens += accessToken
        }
    }

    private class FakeStore : SessionStore {
        var session: AuthSession? = null
        var writes = 0
        var clears = 0

        override suspend fun read(): AuthSession? = session
        override suspend fun write(session: AuthSession) {
            writes += 1
            this.session = session
        }

        override suspend fun clear() {
            clears += 1
            session = null
        }
    }

    private lateinit var remote: FakeRemote
    private lateinit var store: FakeStore
    private var nowEpochSeconds = 1_000L

    private fun session(
        token: String = "access-1",
        refresh: String = "refresh-1",
        expiresAt: Long = 2_000L,
        userId: String = "user-1",
        email: String = "a@example.com",
    ) = AuthSession(userId, email, token, refresh, expiresAt)

    private fun controller() = AuthController(remote, store) { nowEpochSeconds }

    @Before
    fun setUp() {
        remote = FakeRemote()
        store = FakeStore()
    }

    @Test
    fun `start is signed out when no session is stored`() = runTest {
        controller().start()
        assertEquals(AuthState.SignedOut, controller().state.value)
    }

    @Test
    fun `start restores a stored unexpired session without a network call`() = runTest {
        store.session = session(expiresAt = 2_000L)
        val controller = controller()
        controller.start()
        assertEquals(AuthState.SignedIn(session()), controller.state.value)
        assertEquals(0, remote.refreshCalls)
    }

    @Test
    fun `sign up with auto-confirm signs in and persists the session`() = runTest {
        remote.signUpResult = RemoteAuth.Session(session())
        val controller = controller()
        val result = controller.signUp("a@example.com", "pw")
        assertTrue(result is AuthResult.Success)
        assertEquals(AuthState.SignedIn(session()), controller.state.value)
        assertEquals(session(), store.session)
    }

    @Test
    fun `sign up awaiting verification does not persist a session`() = runTest {
        remote.signUpResult = RemoteAuth.VerificationRequired("a@example.com")
        val controller = controller()
        val result = controller.signUp("a@example.com", "pw")
        assertTrue(result is AuthResult.Success)
        assertEquals(AuthState.AwaitingVerification("a@example.com"), controller.state.value)
        assertNull(store.session)
    }

    @Test
    fun `sign in signs in and persists the session`() = runTest {
        remote.signInResult = session()
        val controller = controller()
        val result = controller.signIn("a@example.com", "pw")
        assertTrue(result is AuthResult.Success)
        assertEquals(AuthState.SignedIn(session()), controller.state.value)
        assertEquals(session(), store.session)
    }

    @Test
    fun `invalid credentials surfaces a typed failure and stays signed out`() = runTest {
        remote.signInError = AuthError.InvalidCredentials
        val controller = controller()
        val result = controller.signIn("a@example.com", "wrong")
        assertEquals(AuthResult.Failure(AuthError.InvalidCredentials), result)
        assertEquals(AuthState.SignedOut, controller.state.value)
        assertNull(store.session)
    }

    @Test
    fun `start refreshes an expired stored session`() = runTest {
        store.session = session(token = "old", refresh = "refresh-1", expiresAt = 500L)
        nowEpochSeconds = 1_000L
        val refreshed = session(token = "new", refresh = "refresh-2", expiresAt = 3_000L)
        remote.refreshResult = refreshed
        val controller = controller()
        controller.start()
        assertEquals(AuthState.SignedIn(refreshed), controller.state.value)
        assertEquals(refreshed, store.session)
        assertEquals(1, remote.refreshCalls)
    }

    @Test
    fun `start clears the session when refresh fails`() = runTest {
        store.session = session(expiresAt = 500L)
        nowEpochSeconds = 1_000L
        remote.refreshError = AuthError.InvalidCredentials
        val controller = controller()
        controller.start()
        assertEquals(AuthState.SignedOut, controller.state.value)
        assertEquals(1, store.clears)
        assertNull(store.session)
    }

    @Test
    fun `sign out clears the session, calls remote logout, and never touches the collection`() = runTest {
        remote.signInResult = session()
        val controller = controller()
        controller.signIn("a@example.com", "pw")

        controller.signOut()

        assertEquals(AuthState.SignedOut, controller.state.value)
        assertEquals(listOf("access-1"), remote.signedOutTokens)
        assertNull(store.session)
        assertEquals(1, store.clears)
    }

    @Test
    fun `sign out is safe when remote logout fails`() = runTest {
        remote.signInResult = session()
        remote.signOutThrows = true
        val controller = controller()
        controller.signIn("a@example.com", "pw")

        controller.signOut()

        assertEquals(AuthState.SignedOut, controller.state.value)
        assertNull(store.session)
        assertEquals(1, store.clears)
    }
}
