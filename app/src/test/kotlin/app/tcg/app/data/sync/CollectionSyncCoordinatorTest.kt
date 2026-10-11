package app.tcg.app.data.sync

import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionSyncCoordinatorTest {
    @Test
    fun identity_binding_uses_cross_thread_safe_publication() {
        val field = CollectionSyncCoordinator::class.java.getDeclaredField("identity")

        assertTrue(
            Modifier.isVolatile(field.modifiers) || AtomicReference::class.java.isAssignableFrom(field.type),
        )
    }

    @Test
    fun sync_propagates_cancellation_from_transport() = runTest {
        val local = TestLocalSyncStore()
        val transport = object : SyncTransport {
            override suspend fun push(identity: String, operations: List<SyncOperation>): List<PushOutcome> =
                throw CancellationException("cancel sync")

            override suspend fun pull(identity: String, afterSequence: Long): PullPage =
                error("pull must not run after cancelled push")
        }
        val coordinator = CollectionSyncCoordinator(transport, local)
        coordinator.bindIdentity("user-a")

        val failure = runCatching { coordinator.syncOnce() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    @Test
    fun two_devices_exchange_create_and_retry_after_transport_failure() = runTest {
        val transport = InMemorySyncTransport(failNextPush = true)
        val first = TestLocalSyncStore()
        val second = TestLocalSyncStore()
        val coordinatorA = CollectionSyncCoordinator(transport, first)
        val coordinatorB = CollectionSyncCoordinator(transport, second)
        val item = SyncItem("item-1", "card-1", 2, CardVariant.NORMAL, Condition.NEAR_MINT, Money(1.5, Currency.USD), 10L)
        first.enqueue(SyncOperation.Create("op-1", "device-a", item))
        coordinatorA.bindIdentity("user-a")
        coordinatorB.bindIdentity("user-a")

        assertEquals(SyncResult.Error(pending = 1), coordinatorA.syncOnce())
        assertEquals(SyncResult.Complete(applied = 1, pending = 0), coordinatorA.syncOnce())
        assertEquals(SyncResult.Complete(applied = 0, pending = 0), coordinatorB.syncOnce())
        assertEquals(item, second.items["item-1"])
        assertEquals(1, transport.sequence("user-a"))
    }

    @Test
    fun stale_replace_and_tombstone_conflicts_remain_queued() = runTest {
        val transport = InMemorySyncTransport()
        val local = TestLocalSyncStore()
        val coordinator = CollectionSyncCoordinator(transport, local)
        val item = SyncItem("item-1", "card-1", 1, CardVariant.NORMAL, Condition.NEAR_MINT, null, 10L)
        transport.push("user-a", listOf(SyncOperation.Create("remote-create", "device-b", item)))
        transport.push("user-a", listOf(SyncOperation.Delete("remote-delete", "device-b", 1, "item-1")))
        local.enqueue(SyncOperation.Replace("stale-replace", "device-a", 1, item.copy(quantity = 3)))
        coordinator.bindIdentity("user-a")

        val result = coordinator.syncOnce()

        assertEquals(1, (result as SyncResult.Conflicts).count)
        assertEquals(1, result.pending)
        assertEquals(1, local.pending("user-a").size)
        assertTrue(local.items.isEmpty())
    }

    @Test
    fun accepted_operation_replays_idempotently_when_local_acknowledgement_fails() = runTest {
        val transport = InMemorySyncTransport()
        val local = TestLocalSyncStore()
        val coordinator = CollectionSyncCoordinator(transport, local)
        local.enqueue(SyncOperation.Create("op-retry", "device-a", SyncItem("item-r", "card-r", 1, CardVariant.NORMAL, Condition.NEAR_MINT, null, 10L)))
        local.failNextAcknowledge = true
        coordinator.bindIdentity("user-a")

        assertEquals(SyncResult.Error(pending = 1), coordinator.syncOnce())
        assertEquals(1, transport.sequence("user-a"))
        assertEquals(SyncResult.Complete(applied = 0, pending = 0), coordinator.syncOnce())
        assertEquals(1, transport.sequence("user-a"))
    }

    @Test
    fun identity_switch_keeps_operations_scoped_and_does_not_advance_other_cursor() = runTest {
        val transport = InMemorySyncTransport()
        val local = TestLocalSyncStore()
        val coordinator = CollectionSyncCoordinator(transport, local)
        local.enqueue(SyncOperation.Create("op-a", "device-a", SyncItem("item-a", "card-a", 1, CardVariant.NORMAL, Condition.NEAR_MINT, null, 1L)), "user-a")
        coordinator.bindIdentity("user-a")
        transport.onPush = { coordinator.bindIdentity("user-b") }

        assertEquals(SyncResult.Offline(pending = 1), coordinator.syncOnce())
        assertEquals(1, local.pending("user-a").size)
        assertTrue(local.pending("user-b").isEmpty())
        assertEquals(0L, local.lastSequence("user-b"))
    }

    @Test
    fun identity_switch_while_loading_pending_operations_prevents_push() = runTest {
        val transport = InMemorySyncTransport()
        val local = TestLocalSyncStore()
        val coordinator = CollectionSyncCoordinator(transport, local)
        local.enqueue(SyncOperation.Create("op-a", "device-a", SyncItem("item-a", "card-a", 1, CardVariant.NORMAL, Condition.NEAR_MINT, null, 1L)), "user-a")
        local.blockNextPending()
        coordinator.bindIdentity("user-a")

        val syncing = async { coordinator.syncOnce() }
        checkNotNull(local.pendingStarted).await()
        coordinator.bindIdentity("user-b")
        checkNotNull(local.releasePending).complete(Unit)

        assertEquals(SyncResult.Offline(pending = 1), syncing.await())
        assertEquals(0, transport.pushCount)
        assertEquals(1, local.pending("user-a").size)
    }
}

private class TestLocalSyncStore : SyncLocalStore {
    private val operationsByIdentity = mutableMapOf<String, MutableList<SyncOperation>>()
    private val cursors = mutableMapOf<String, Long>()
    val items = mutableMapOf<String, SyncItem>()
    var failNextAcknowledge = false
    var pendingStarted: CompletableDeferred<Unit>? = null
    var releasePending: CompletableDeferred<Unit>? = null

    fun blockNextPending() {
        pendingStarted = CompletableDeferred()
        releasePending = CompletableDeferred()
    }

    fun enqueue(operation: SyncOperation, identity: String = "user-a") {
        operationsByIdentity.getOrPut(identity, ::mutableListOf).add(operation)
    }

    override suspend fun pending(identity: String): List<SyncOperation> {
        pendingStarted?.complete(Unit)
        releasePending?.await()
        pendingStarted = null
        releasePending = null
        return operationsByIdentity[identity].orEmpty().toList()
    }

    override suspend fun acknowledge(identity: String, opId: String): Boolean {
        if (failNextAcknowledge) {
            failNextAcknowledge = false
            return false
        }
        return operationsByIdentity[identity]?.removeAll { it.opId == opId } ?: false
    }

    override suspend fun applyRemote(identity: String, entry: SequencedOperation): Boolean {
        when (val operation = entry.operation) {
            is SyncOperation.Create -> items[operation.item.id] = operation.item
            is SyncOperation.Replace -> items[operation.item.id] = operation.item
            is SyncOperation.Delete -> items.remove(operation.itemId)
        }
        return true
    }

    override suspend fun lastSequence(identity: String): Long = cursors[identity] ?: 0

    override suspend fun saveSequence(identity: String, sequence: Long): Boolean {
        cursors[identity] = sequence
        return true
    }
}
