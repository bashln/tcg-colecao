package app.tcg.app.data.sync

import app.tcg.app.auth.AuthState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Task 5 seam: the lifecycle controller decides WHEN to sync (bind, local write,
 * foreground, network) and guarantees single-flight; the coordinator decides HOW.
 * Tests drive it with a counting/gating lambda so the concurrency contract is visible.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncLifecycleTest {
    @Test
    fun conflict_resolution_uses_lifecycle_identity_and_publishes_result() = runTest {
        var request: Pair<String, SyncConflictResolution>? = null
        val lifecycle = SyncLifecycle(
            scope = this,
            resolveConflictAction = { conflictId, resolution ->
                request = conflictId to resolution
                SyncResult.Complete(applied = 1, pending = 0)
            },
            syncAction = { SyncResult.Complete(applied = 0, pending = 0) },
        )
        lifecycle.bindIdentity("user-a")

        val result = lifecycle.resolveConflict("user-a:5", SyncConflictResolution.KeepCloud)
        advanceUntilIdle()

        assertEquals(SyncResult.Complete(applied = 1, pending = 0), result.await())
        assertEquals("user-a:5" to SyncConflictResolution.KeepCloud, request)
        assertEquals(SyncResult.Complete(applied = 0, pending = 0), lifecycle.status.value.result)
    }

    @Test
    fun unexpected_sync_exception_marks_pending_count_unavailable() = runTest {
        val lifecycle = SyncLifecycle(
            scope = this,
            syncAction = { throw IllegalStateException("coordinator failed unexpectedly") },
        )
        lifecycle.bindIdentity("user-a")
        val run = lifecycle.request()
        advanceUntilIdle()

        assertEquals(SyncResult.Error(pending = null), run.await())
    }


    @Test
    fun `concurrent request producers resolve every queued request`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requests = ConcurrentLinkedQueue<kotlinx.coroutines.Deferred<SyncResult>>()
        val failure = AtomicReference<Throwable?>(null)
        val gate = CountDownLatch(1)
        val lifecycle = SyncLifecycle(scope) {
            kotlinx.coroutines.delay(1)
            SyncResult.Complete(applied = 0, pending = 0)
        }
        lifecycle.bindIdentity("user-a")
        val producers = List(8) {
            Thread {
                try {
                    gate.await()
                    repeat(100) { requests.add(lifecycle.request()) }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }
        }

        try {
            producers.forEach(Thread::start)
            gate.countDown()
            producers.forEach(Thread::join)
            failure.get()?.let { throw it }
            val pending = requests.toList()
            withTimeout(10_000) { pending.forEach { it.await() } }
            assertEquals(800, pending.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `binding changes propagate to the coordinator identity`() = runTest {
        val identities = mutableListOf<String?>()
        val lifecycle = SyncLifecycle(
            scope = this,
            syncAction = { SyncResult.Complete(applied = 0, pending = 0) },
            onIdentityChanged = identities::add,
        )

        lifecycle.refreshBinding(signedIn("user-a"), linkedIdentity = "user-a")
        lifecycle.refreshBinding(AuthState.SignedOut, linkedIdentity = "user-a")

        assertEquals(listOf("user-a", null), identities)
    }

    @Test
    fun `binding can refresh from store using lifecycle scope`() = runTest {
        val identities = mutableListOf<String?>()
        val lifecycle = SyncLifecycle(
            scope = this,
            onIdentityChanged = identities::add,
            syncAction = { SyncResult.Complete(applied = 0, pending = 0) },
        )

        lifecycle.refreshBindingFromStore(
            authState = { signedIn("user-a") },
            readLinkedIdentity = { "user-a" },
        )
        advanceUntilIdle()

        assertEquals(listOf("user-a"), identities)
    }

    @Test
    fun `confirmed link persists before lifecycle reads and binds identity`() = runTest {
        val events = mutableListOf<String>()
        val lifecycle = SyncLifecycle(
            scope = this,
            onIdentityChanged = { events += "bound:$it" },
            syncAction = { SyncResult.Complete(applied = 0, pending = 0) },
        )

        lifecycle.persistAndRefreshBinding(
            expectedIdentity = "user-a",
            authState = { signedIn("user-a") },
            persistBinding = { events += "persisted" },
            readLinkedIdentity = {
                events += "read"
                "user-a"
            },
            clearBinding = { events += "cleared" },
        )
        advanceUntilIdle()

        assertEquals(listOf("persisted", "read", "bound:user-a"), events)
    }

    @Test
    fun `unlink racing a suspended link persistence cannot leave a stale binding`() = runTest {
        val persistStarted = CompletableDeferred<Unit>()
        val finishPersist = CompletableDeferred<Unit>()
        var auth: AuthState = signedIn("user-a")
        var storedIdentity: String? = null
        val lifecycle = SyncLifecycle(this) { SyncResult.Complete(applied = 0, pending = 0) }
        val persisted = lifecycle.persistAndRefreshBinding(
            expectedIdentity = "user-a",
            authState = { auth },
            persistBinding = {
                persistStarted.complete(Unit)
                finishPersist.await()
                storedIdentity = "user-a"
            },
            readLinkedIdentity = { storedIdentity },
            clearBinding = { storedIdentity = null },
        )
        runCurrent()
        persistStarted.await()
        auth = AuthState.SignedOut
        lifecycle.unlinkBinding { storedIdentity = null }
        finishPersist.complete(Unit)
        advanceUntilIdle()

        assertFalse(persisted.await())
        assertNull(storedIdentity)
        assertNull(lifecycle.status.value.identity)
    }

    @Test
    fun `unlink after link persistence completes clears the binding`() = runTest {
        var storedIdentity: String? = null
        val lifecycle = SyncLifecycle(this) { SyncResult.Complete(applied = 0, pending = 0) }
        val persisted = lifecycle.persistAndRefreshBinding(
            expectedIdentity = "user-a",
            authState = { signedIn("user-a") },
            persistBinding = { storedIdentity = "user-a" },
            readLinkedIdentity = { storedIdentity },
            clearBinding = { storedIdentity = null },
        )
        advanceUntilIdle()
        assertTrue(persisted.await())

        lifecycle.unlinkBinding { storedIdentity = null }
        advanceUntilIdle()

        assertNull(storedIdentity)
        assertNull(lifecycle.status.value.identity)
    }

    @Test
    fun `unlink triggered during identity publication wins over link completion`() = runTest {
        var storedIdentity: String? = null
        lateinit var lifecycle: SyncLifecycle
        lifecycle = SyncLifecycle(
            scope = this,
            onIdentityChanged = { identity ->
                if (identity == "user-a") lifecycle.unlinkBinding { storedIdentity = null }
            },
            syncAction = { SyncResult.Complete(applied = 0, pending = 0) },
        )
        val persisted = lifecycle.persistAndRefreshBinding(
            expectedIdentity = "user-a",
            authState = { signedIn("user-a") },
            persistBinding = { storedIdentity = "user-a" },
            readLinkedIdentity = { storedIdentity },
            clearBinding = { storedIdentity = null },
        )

        advanceUntilIdle()

        assertFalse(persisted.await())
        assertNull(storedIdentity)
        assertNull(lifecycle.status.value.identity)
    }

    private fun signedIn(userId: String) = AuthState.SignedIn(
        app.tcg.app.auth.AuthSession(userId, "$userId@example.com", "at", "rt", 5_000L),
    )


    @Test
    fun `overlapping triggers never run the coordinator concurrently and all resolve`() = runTest {
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        var running = 0
        var maxRunning = 0
        var calls = 0
        val lifecycle = SyncLifecycle(this) {
            calls += 1
            running += 1
            maxRunning = maxOf(maxRunning, running)
            if (calls == 1) {
                val gate = CompletableDeferred<Unit>()
                gates += gate
                gate.await()
            }
            running -= 1
            SyncResult.Complete(applied = 1, pending = 0)
        }
        lifecycle.bindIdentity("user-a")
        advanceUntilIdle()
        assertEquals(1, calls)

        val second = lifecycle.request()
        val third = lifecycle.request()
        runCurrent()
        gates[0].complete(Unit)
        advanceUntilIdle()

        assertTrue("never concurrent", maxRunning == 1)
        assertTrue("a trailing run covers the queued requests", calls == 2)
        second.await()
        third.await()
    }

    @Test
    fun `status exposes running then the settled result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val lifecycle = SyncLifecycle(this) {
            calls += 1
            if (calls == 1) gate.await()
            SyncResult.Complete(applied = 0, pending = 0)
        }
        lifecycle.bindIdentity("user-a")
        val seen = mutableListOf<String>()
        val job = launch { lifecycle.status.collect { seen += it.running.toString() } }

        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        job.cancel()

        assertTrue("running is published while a run is live", seen.contains("true"))
    }

    @Test
    fun `a thrown sync reports an error result and keeps working`() = runTest {
        var calls = 0
        val lifecycle = SyncLifecycle(this) {
            calls += 1
            if (calls == 1) error("boom")
            SyncResult.Complete(applied = 1, pending = 0)
        }
        lifecycle.bindIdentity("user-a")

        assertTrue(lifecycle.request().await() is SyncResult.Error)
    }

    @Test
    fun `unbinding stops automatic triggers from starting a run`() = runTest {
        var calls = 0
        val lifecycle = SyncLifecycle(this) {
            calls += 1
            SyncResult.Complete(applied = 0, pending = 0)
        }

        lifecycle.onLocalWrite()
        lifecycle.onForeground()
        lifecycle.onNetworkAvailable()
        advanceUntilIdle()

        assertFalse("no run without a bound identity", calls > 0)
    }

    @Test
    fun `binding triggers an initial sync exactly once`() = runTest {
        var calls = 0
        val lifecycle = SyncLifecycle(this) {
            calls += 1
            SyncResult.Complete(applied = 0, pending = 0)
        }

        lifecycle.bindIdentity("user-a")
        advanceUntilIdle()

        assertEquals(1, calls)
    }

    @Test
    fun `refreshBinding with a sign-out unbinds so triggers no longer sync`() = runTest {
        var calls = 0
        val lifecycle = SyncLifecycle(this) {
            calls += 1
            SyncResult.Complete(applied = 0, pending = 0)
        }
        lifecycle.refreshBinding(signedIn("user-a"), linkedIdentity = "user-a")
        advanceUntilIdle()
        assertEquals(1, calls)

        lifecycle.refreshBinding(AuthState.SignedOut, linkedIdentity = "user-a")
        lifecycle.onLocalWrite()
        lifecycle.onForeground()
        advanceUntilIdle()

        assertEquals("unbound after sign-out: no further run", 1, calls)
    }

    @Test
    fun `refreshBinding with another account's binding stays unbound`() = runTest {
        var calls = 0
        val lifecycle = SyncLifecycle(this) {
            calls += 1
            SyncResult.Complete(applied = 0, pending = 0)
        }
        lifecycle.refreshBinding(signedIn("user-b"), linkedIdentity = "user-a")
        advanceUntilIdle()

        assertEquals("must never sync under a different identity", 0, calls)
    }

    @Test
    fun `automatic triggers coalesce into a single trailing run`() = runTest {
        var calls = 0
        val lifecycle = SyncLifecycle(this) {
            calls += 1
            SyncResult.Complete(applied = 0, pending = 0)
        }
        lifecycle.bindIdentity("user-a")
        advanceUntilIdle()
        assertEquals(1, calls)

        lifecycle.onLocalWrite()
        lifecycle.onLocalWrite()
        lifecycle.onNetworkAvailable()
        advanceUntilIdle()

        assertEquals("three triggers while idle still make one run", 2, calls)
    }
}
