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
import app.tcg.app.data.sync.LinkCommitResult
import app.tcg.app.data.sync.LinkConflict
import app.tcg.app.data.sync.LinkOutcome
import app.tcg.app.data.sync.LinkPreview
import app.tcg.app.data.sync.LinkPreviewSummary
import app.tcg.app.data.sync.RemoteLinkItem
import app.tcg.app.data.sync.SyncLinkCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
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

    private class FakeLinkCoordinator : SyncLinkCoordinator {
        var summary: LinkPreviewSummary? = null
        var prepareError: Exception? = null
        var outcome: LinkOutcome = LinkOutcome.Linked(9L)
        val confirmed = mutableListOf<Pair<String, LinkPreview>>()

        override suspend fun preparePreview(): LinkPreviewSummary {
            prepareError?.let { throw it }
            return checkNotNull(summary)
        }

        override suspend fun confirm(identity: String, plan: LinkPreview): LinkOutcome {
            confirmed += identity to plan
            return outcome
        }
    }

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

    private fun linkPlan(
        localOnly: List<String> = listOf("item-1"),
        cloudOnly: List<String> = emptyList(),
        conflicting: List<LinkConflict> = emptyList(),
        identical: List<String> = emptyList(),
        remoteItems: List<RemoteLinkItem> = emptyList(),
    ) = LinkPreview(
        planId = "plan-1",
        digest = "digest-1",
        expiresAt = "2026-10-12T20:00:00Z",
        remoteHighWater = 7L,
        localOnly = localOnly,
        cloudOnly = cloudOnly,
        identical = identical,
        conflicting = conflicting,
        duplicateConfigurations = emptyList(),
        remoteItems = remoteItems,
    )

    private val userA = AuthSession("user-a", "a@example.com", "at-a", "rt-a", 5_000L)
    private val userB = AuthSession("user-b", "b@example.com", "at-b", "rt-b", 5_000L)

    private lateinit var remote: FakeRemote
    private lateinit var store: FakeStore
    private lateinit var link: FakeLinkCoordinator
    private lateinit var syncStatus: MutableStateFlow<app.tcg.app.data.sync.SyncStatus>
    private var syncNowCalls = 0
    private lateinit var vm: AccountViewModel
    private val linked = mutableListOf<String>()
    private var unlinked = 0

    @Before
    fun setUp() {
        remote = FakeRemote()
        store = FakeStore()
        linked.clear()
        unlinked = 0
        link = FakeLinkCoordinator()
        syncStatus = MutableStateFlow(app.tcg.app.data.sync.SyncStatus())
        syncNowCalls = 0
        vm = AccountViewModel(
            auth = AuthController(remote, store) { 1_000L },
            link = link,
            syncStatus = syncStatus,
            onSyncNow = { syncNowCalls += 1 },
            onLinkConfirmed = { linked += it; true },
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
    fun `requesting a link fetches the server preview and shows its counts`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")
        link.summary = LinkPreviewSummary(
            plan = linkPlan(localOnly = listOf("item-1"), cloudOnly = listOf("item-2"), identical = listOf("item-3")),
            localOnlyCount = 1, cloudOnlyCount = 1, identicalCount = 1, conflictingCount = 0, duplicateCount = 0,
        )

        vm.requestLink()
        advanceUntilIdle()

        val preview = vm.state.value.linkState as LinkState.Preview
        assertEquals(1, preview.summary.localOnlyCount)
        assertEquals(1, preview.summary.cloudOnlyCount)
        assertEquals(1, preview.summary.identicalCount)
        assertTrue("no data may be sent before confirmation", linked.isEmpty())
    }

    @Test
    fun `confirming the link commits the plan and binds the identity`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")
        val plan = linkPlan()
        link.summary = LinkPreviewSummary(plan, 1, 0, 0, 0, 0)
        vm.requestLink()
        advanceUntilIdle()

        link.outcome = LinkOutcome.Linked(9L)
        vm.confirmLink()
        advanceUntilIdle()

        assertEquals(LinkState.Linked("user-a"), vm.state.value.linkState)
        assertEquals(listOf("user-a"), linked)
        assertEquals(listOf("user-a" to plan), link.confirmed)
    }

    @Test
    fun `a preview with conflicting rows is blocked and stays in preview`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")
        link.summary = LinkPreviewSummary(
            plan = linkPlan(conflicting = listOf(LinkConflict("item-1", "different-content"))),
            localOnlyCount = 0, cloudOnlyCount = 0, identicalCount = 0, conflictingCount = 1, duplicateCount = 0,
        )
        vm.requestLink()
        advanceUntilIdle()

        link.outcome = LinkOutcome.Blocked
        vm.confirmLink()
        advanceUntilIdle()

        assertTrue("blocked link must not bind", linked.isEmpty())
        assertTrue(vm.state.value.linkState is LinkState.Preview)
        assertEquals(AccountError.LINK_BLOCKED, vm.state.value.error)
    }

    @Test
    fun `a stale preview keeps local-only state and explains why`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")
        link.summary = LinkPreviewSummary(linkPlan(), 1, 0, 0, 0, 0)
        vm.requestLink()
        advanceUntilIdle()

        link.outcome = LinkOutcome.LocalChanged
        vm.confirmLink()
        advanceUntilIdle()

        assertTrue(linked.isEmpty())
        assertEquals(LinkState.LocalOnly, vm.state.value.linkState)
        assertEquals(AccountError.LINK_STALE, vm.state.value.error)
    }

    @Test
    fun `a partially committed link warns that cloud may already contain seeded items`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")
        link.summary = LinkPreviewSummary(linkPlan(), 1, 0, 0, 0, 0)
        vm.requestLink()
        advanceUntilIdle()

        link.outcome = LinkOutcome.Partial
        vm.confirmLink()
        advanceUntilIdle()

        assertEquals(LinkState.LocalOnly, vm.state.value.linkState)
        assertEquals(AccountError.LINK_PARTIAL, vm.state.value.error)
        assertTrue(linked.isEmpty())
    }

    @Test
    fun `a prepare failure surfaces a network error and stays local-only`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")
        link.prepareError = RuntimeException("offline")

        vm.requestLink()
        advanceUntilIdle()

        assertEquals(LinkState.LocalOnly, vm.state.value.linkState)
        assertEquals(AccountError.NETWORK, vm.state.value.error)
        assertTrue(linked.isEmpty())
    }

    @Test
    fun `cancelling the link preserves local-only state and sends nothing`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")
        val plan = linkPlan()
        link.summary = LinkPreviewSummary(plan, 1, 0, 0, 0, 0)
        vm.requestLink()
        advanceUntilIdle()
        assertEquals(LinkState.Preview(summary = LinkPreviewSummary(plan, 1, 0, 0, 0, 0)), vm.state.value.linkState)

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
        val plan = linkPlan()
        link.summary = LinkPreviewSummary(plan, 1, 0, 0, 0, 0)
        vm.requestLink()
        advanceUntilIdle()
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
        val plan = linkPlan()
        link.summary = LinkPreviewSummary(plan, 1, 0, 0, 0, 0)
        vm.requestLink()
        advanceUntilIdle()
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
    fun `sync status surfaces running then the settled pending count`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")

        syncStatus.value = app.tcg.app.data.sync.SyncStatus(identity = "user-a", running = true)
        advanceUntilIdle()
        assertEquals(SyncStatusView.Running, vm.state.value.syncStatus)

        syncStatus.value = app.tcg.app.data.sync.SyncStatus(
            identity = "user-a",
            running = false,
            result = app.tcg.app.data.sync.SyncResult.Complete(applied = 1, pending = 2),
        )
        advanceUntilIdle()
        assertEquals(SyncStatusView.Synced(pending = 2), vm.state.value.syncStatus)
    }

    @Test
    fun `sync conflicts stay visible with their details`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")

        syncStatus.value = app.tcg.app.data.sync.SyncStatus(
            identity = "user-a",
            running = false,
            result = app.tcg.app.data.sync.SyncResult.Conflicts(
                count = 1,
                pending = 1,
                details = listOf(app.tcg.app.data.sync.SyncConflictDetail("item-1", "stale-revision")),
            ),
        )
        advanceUntilIdle()

        val status = vm.state.value.syncStatus
        assertTrue("conflicts must be visible", status is SyncStatusView.Conflicts)
        assertEquals(listOf("item-1"), (status as SyncStatusView.Conflicts).details.map { it.itemId })
    }

    @Test
    fun `offline and error are distinguishable to the person`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")

        syncStatus.value = app.tcg.app.data.sync.SyncStatus(
            identity = "user-a", running = false,
            result = app.tcg.app.data.sync.SyncResult.Offline(pending = 3),
        )
        advanceUntilIdle()
        assertEquals(SyncStatusView.Offline(pending = 3), vm.state.value.syncStatus)

        syncStatus.value = app.tcg.app.data.sync.SyncStatus(
            identity = "user-a", running = false,
            result = app.tcg.app.data.sync.SyncResult.Error(pending = 3),
        )
        advanceUntilIdle()
        assertEquals(SyncStatusView.Failed(pending = 3), vm.state.value.syncStatus)
    }

    @Test
    fun `manual sync request delegates to the lifecycle`() = runTest {
        remote.signInSession = userA
        signInAs("a@example.com")

        vm.syncNow()
        advanceUntilIdle()

        assertEquals(1, syncNowCalls)
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
