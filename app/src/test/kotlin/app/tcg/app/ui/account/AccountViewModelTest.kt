package app.tcg.app.ui.account

import app.tcg.app.auth.AuthController
import app.tcg.app.auth.AuthError
import app.tcg.app.auth.AuthRemote
import app.tcg.app.auth.AuthRemoteException
import app.tcg.app.auth.AuthResult
import app.tcg.app.auth.AuthSession
import app.tcg.app.auth.RemoteAuth
import app.tcg.app.auth.SessionStore
import app.tcg.app.testutil.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeRemote : AuthRemote {
        var signInSession: AuthSession? = null
        var signInError: AuthError? = null
        var signUpResult: RemoteAuth? = null
        var signUpError: AuthError? = null
        var gate: CompletableDeferred<Unit>? = null
        val signedOutTokens = mutableListOf<String>()

        override suspend fun signUp(email: String, password: String): RemoteAuth {
            gate?.await()
            signUpError?.let { throw AuthRemoteException(it, "signup") }
            return checkNotNull(signUpResult)
        }

        override suspend fun signIn(email: String, password: String): AuthSession {
            gate?.await()
            signInError?.let { throw AuthRemoteException(it, "signin") }
            return checkNotNull(signInSession)
        }

        override suspend fun refresh(refreshToken: String): AuthSession = error("unused")

        override suspend fun signOut(accessToken: String) {
            signedOutTokens += accessToken
        }
    }

    private class FakeStore : SessionStore {
        var session: AuthSession? = null
        override suspend fun read(): AuthSession? = session
        override suspend fun write(session: AuthSession) {
            this.session = session
        }

        override suspend fun clear() {
            session = null
        }
    }

    private val userA = AuthSession("user-a", "a@example.com", "at-a", "rt-a", 5_000L)
    private val userB = AuthSession("user-b", "b@example.com", "at-b", "rt-b", 5_000L)

    private lateinit var remote: FakeRemote
    private lateinit var store: FakeStore
    private lateinit var vm: AccountViewModel
    private val linked = mutableListOf<String>()
    private var unlinked = 0

    @Before
    fun setUp() {
        remote = FakeRemote()
        store = FakeStore()
        linked.clear()
        unlinked = 0
        vm = AccountViewModel(
            auth = AuthController(remote, store) { 1_000L },
            onLinkConfirmed = { linked += it },
            onLinkUnlinked = { unlinked += 1 },
        )
    }

    @Test
    fun `initial state is signed out, local-only, no error`() = runTest {
        val state = vm.state.value
        assertFalse(state.signedIn)
        assertEquals(LinkState.LocalOnly, state.linkState)
        assertNull(state.error)
        assertFalse(state.submitting)
    }

    @Test
    fun `successful sign in is signed in but stays local-only until linked`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")
        advanceUntilIdle()

        assertTrue(vm.state.value.signedIn)
        assertEquals("a@example.com", vm.state.value.email)
        assertEquals(LinkState.LocalOnly, vm.state.value.linkState)
        assertTrue(linked.isEmpty())
    }

    @Test
    fun `invalid credentials surfaces a friendly error and stays signed out`() = runTest {
        remote.signInError = AuthError.InvalidCredentials
        signInAs("a@example.com")
        advanceUntilIdle()

        assertFalse(vm.state.value.signedIn)
        assertEquals(AccountError.INVALID_CREDENTIALS, vm.state.value.error)
        assertFalse(vm.state.value.submitting)
    }

    @Test
    fun `sign up awaiting verification exposes the pending email`() = runTest {
        remote.signUpResult = RemoteAuth.VerificationRequired("a@example.com")
        vm.submitSignUp("a@example.com", "pw")
        advanceUntilIdle()

        assertEquals("a@example.com", vm.state.value.awaitingVerificationEmail)
        assertFalse(vm.state.value.signedIn)
    }

    @Test
    fun `submitting is true while the call is in flight`() = runTest {
        remote.signInSession = userA
        remote.gate = CompletableDeferred()

        vm.submitSignIn("a@example.com", "pw")
        runCurrent()
        assertTrue(vm.state.value.submitting)

        remote.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.submitting)
        assertTrue(vm.state.value.signedIn)
    }

    @Test
    fun `requesting a link shows the preview without sending anything`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")

        vm.requestLink()
        advanceUntilIdle()

        assertEquals(LinkState.AwaitingConfirmation, vm.state.value.linkState)
        assertTrue("no data may be sent before confirmation", linked.isEmpty())
    }

    @Test
    fun `confirming the link binds the identity`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")
        vm.requestLink()
        advanceUntilIdle()

        vm.confirmLink()
        advanceUntilIdle()

        assertEquals(LinkState.Linked("user-a"), vm.state.value.linkState)
        assertEquals(listOf("user-a"), linked)
    }

    @Test
    fun `cancelling the link preserves local-only state and sends nothing`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")
        vm.requestLink()
        advanceUntilIdle()
        assertEquals(LinkState.AwaitingConfirmation, vm.state.value.linkState)

        vm.cancelLink()
        advanceUntilIdle()

        assertEquals(LinkState.LocalOnly, vm.state.value.linkState)
        assertTrue(linked.isEmpty())
        assertTrue(vm.state.value.signedIn)
    }

    @Test
    fun `cannot request a link while signed out`() = runTest {
        vm.requestLink()
        advanceUntilIdle()
        assertEquals(LinkState.LocalOnly, vm.state.value.linkState)
    }

    @Test
    fun `sign out clears the link and preserves local collection`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")
        vm.requestLink()
        vm.confirmLink()
        advanceUntilIdle()
        assertEquals(LinkState.Linked("user-a"), vm.state.value.linkState)

        vm.signOut()
        advanceUntilIdle()

        assertFalse(vm.state.value.signedIn)
        assertEquals(LinkState.LocalOnly, vm.state.value.linkState)
        assertEquals(1, unlinked)
        assertEquals(listOf("at-a"), remote.signedOutTokens)
    }

    @Test
    fun `switching accounts forces a new preview and never mixes identities`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")
        vm.requestLink()
        vm.confirmLink()
        advanceUntilIdle()
        assertEquals(LinkState.Linked("user-a"), vm.state.value.linkState)

        // Switch to B: sign out then sign in as a different user.
        vm.signOut()
        remote.signInSession = userB
        signInAs("b@example.com")
        advanceUntilIdle()

        // B is signed in but must re-run preview + confirmation; never auto-linked.
        assertTrue(vm.state.value.signedIn)
        assertEquals("b@example.com", vm.state.value.email)
        assertEquals(LinkState.LocalOnly, vm.state.value.linkState)
        assertEquals("only A was ever linked", listOf("user-a"), linked)
    }

    @Test
    fun `dismiss error clears it`() = runTest {
        remote.signInError = AuthError.Network
        signInAs("a@example.com")
        advanceUntilIdle()
        assertEquals(AccountError.NETWORK, vm.state.value.error)

        vm.dismissError()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
    }

    private fun kotlinx.coroutines.test.TestScope.signInAs(email: String) {
        vm.submitSignIn(email, "pw")
        advanceUntilIdle()
    }
}
