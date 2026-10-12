package app.tcg.app.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.tcg.app.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class SyncStatus(
    val identity: String? = null,
    val running: Boolean = false,
    val result: SyncResult? = null,
)

/**
 * Single-flight sync lifecycle. It owns WHEN a sync happens — identity binding, local
 * writes, foreground, and network recovery — and guarantees two triggers never overlap:
 * requests that arrive mid-run coalesce into exactly one trailing run. The coordinator
 * (via [syncAction]) owns HOW a sync works. Unbinding cancels automatic triggers without
 * touching any local data.
 */
class SyncLifecycle(
    private val scope: CoroutineScope,
    private val onIdentityChanged: (String?) -> Unit = {},
    private val resolveConflictAction: suspend (String, SyncConflictResolution) -> SyncResult = { _, _ -> SyncResult.Error(0) },
    private val syncAction: suspend (SyncResult?) -> SyncResult,
) {
    private val identity = AtomicReference<String?>(null)
    private val bindingGeneration = AtomicLong(0)
    private val bindingTransitions = Mutex()
    private val syncTransitions = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Requests a sync; the returned deferred resolves with the run that covered this request. */
    fun request(): Deferred<SyncResult> {
        val pending = CompletableDeferred<SyncResult>()
        enqueue(pending)
        return pending
    }

    fun resolveConflict(conflictId: String, resolution: SyncConflictResolution): Deferred<SyncResult> = scope.async {
        val expectedIdentity = identity.get() ?: return@async SyncResult.Offline(0)
        syncTransitions.withLock {
            if (identity.get() != expectedIdentity) return@withLock SyncResult.Offline(0)
            _status.value = _status.value.copy(running = true)
            val result = try {
                resolveConflictAction(conflictId, resolution)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SyncResult.Error(null)
            }
            _status.value = _status.value.copy(running = false, result = result)
            result
        }
    }

    /**
     * Recomputes the bound identity from the live auth state plus this device's stored
     * binding. A sign-out or account switch resolves to null here, which unbinds before any
     * further trigger can run — so a previous identity can never be pushed under.
     */
    fun refreshBinding(auth: AuthState, linkedIdentity: String?) {
        bindIdentity(resolveSyncIdentity(auth, linkedIdentity))
    }

    /** Re-reads binding after a link using this lifecycle's application-owned scope. */
    fun refreshBindingFromStore(authState: () -> AuthState, readLinkedIdentity: suspend () -> String?) {
        scope.launch {
            val linkedIdentity = readLinkedIdentity()
            refreshBinding(authState(), linkedIdentity)
        }
    }

    /** Persist confirmed link and refresh coordinator identity within application scope. */
    fun persistAndRefreshBinding(
        expectedIdentity: String,
        authState: () -> AuthState,
        persistBinding: suspend () -> Unit,
        readLinkedIdentity: suspend () -> String?,
        clearBinding: suspend () -> Unit,
    ): Deferred<Boolean> {
        val generation = bindingGeneration.get()
        return scope.async {
            bindingTransitions.withLock {
                if (generation != bindingGeneration.get() || currentIdentity(authState()) != expectedIdentity) return@withLock false
                try {
                    persistBinding()
                    val linkedIdentity = readLinkedIdentity()
                    if (generation != bindingGeneration.get() || currentIdentity(authState()) != expectedIdentity || linkedIdentity != expectedIdentity) {
                        if (linkedIdentity == expectedIdentity) clearBinding()
                        bindIdentity(null)
                        return@withLock false
                    }
                    refreshBinding(authState(), linkedIdentity)
                    if (generation != bindingGeneration.get() || currentIdentity(authState()) != expectedIdentity) {
                        clearBinding()
                        bindIdentity(null)
                        return@withLock false
                    }
                    true
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    fun unlinkBinding(unlink: suspend () -> Unit) {
        bindingGeneration.incrementAndGet()
        bindIdentity(null)
        scope.launch {
            bindingTransitions.withLock {
                try {
                    unlink()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    bindIdentity(null)
                }
            }
        }
    }

    private fun currentIdentity(auth: AuthState): String? = (auth as? AuthState.SignedIn)?.session?.userId

    fun bindIdentity(nextIdentity: String?) {
        identity.set(nextIdentity)
        _status.value = _status.value.copy(identity = nextIdentity)
        onIdentityChanged(nextIdentity)
        if (nextIdentity != null) trigger()
    }

    fun onLocalWrite() = trigger()

    fun onForeground() = trigger()

    fun onNetworkAvailable() = trigger()

    private fun trigger() {
        if (identity.get() == null) return
        scope.launch { request().await() }
    }

    /** Serializes runs: only one drain loop is live, and it coalesces queued requests. */
    private fun enqueue(pending: CompletableDeferred<SyncResult>) {
        synchronized(waiting) {
            waiting.addLast(pending)
            if (drainJob?.isActive == true) return
            drainJob = scope.launch { drain() }
        }
    }

    private val waiting = ArrayDeque<CompletableDeferred<SyncResult>>()
    private var drainJob: Job? = null

    private suspend fun drain() {
        while (true) {
            val batch = synchronized(waiting) {
                if (waiting.isEmpty()) {
                    drainJob = null
                    null
                } else {
                    val queued = waiting.toList()
                    waiting.clear()
                    identity.get() to queued
                }
            }
            if (batch == null) return
            val (bound, covered) = batch
            if (bound == null) {
                val offline = SyncResult.Offline(0)
                covered.forEach { it.complete(offline) }
                _status.value = _status.value.copy(running = false, result = offline)
                continue
            }
            _status.value = _status.value.copy(running = true)
            val result = try {
                syncTransitions.withLock { syncAction(_status.value.result) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SyncResult.Error(pending = null)
            }
            _status.value = _status.value.copy(running = false, result = result)
            covered.forEach { it.complete(result) }
        }
    }
}
