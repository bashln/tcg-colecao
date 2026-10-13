package app.tcg.app.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tcg.app.data.local.CollectionItemEntity
import app.tcg.app.data.local.SyncOutboxEntity
import app.tcg.app.data.local.TcgDatabase
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomSyncLocalStoreTest {
    private lateinit var db: TcgDatabase
    private lateinit var store: RoomSyncLocalStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TcgDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomSyncLocalStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun syncItem(
        id: String,
        cardId: String = "base1-4",
        quantity: Int = 2,
        variant: CardVariant = CardVariant.NORMAL,
        condition: Condition = Condition.NEAR_MINT,
        pricePaid: Money? = null,
    ) = SyncItem(
        id = id,
        cardId = cardId,
        quantity = quantity,
        variant = variant,
        condition = condition,
        pricePaid = pricePaid,
        addedAt = 1_791_667_200_000L,
    )

    private fun outbox(
        opId: String,
        identity: String,
        kind: String,
        itemId: String,
        baseRevision: Long? = null,
        cardId: String? = "base1-4",
        quantity: Int? = 2,
        condition: String? = Condition.NEAR_MINT.name,
        variant: String? = CardVariant.NORMAL.name,
        pricePaidAmount: Double? = null,
        pricePaidCurrency: String? = null,
        addedAt: Long? = 1_791_667_200_000L,
    ) = SyncOutboxEntity(
        opId = opId,
        identity = identity,
        deviceId = "device-0001",
        itemId = itemId,
        kind = kind,
        baseRevision = baseRevision,
        revision = baseRevision ?: 1,
        cardId = cardId,
        quantity = quantity,
        condition = condition,
        variant = variant,
        pricePaidAmount = pricePaidAmount,
        pricePaidCurrency = pricePaidCurrency,
        addedAt = addedAt,
    )

    @Test
    fun pending_maps_outbox_rows_to_typed_operations() = runTest {
        db.collectionDao().insertSyncOperation(outbox("op-c", "acct", "create", "item-1"))
        db.collectionDao().insertSyncOperation(outbox("op-r", "acct", "replace", "item-1", baseRevision = 3))
        db.collectionDao().insertSyncOperation(
            outbox(
                "op-d",
                "acct",
                "delete",
                "item-2",
                baseRevision = 2,
                cardId = null,
                quantity = null,
                condition = null,
                variant = null,
                pricePaidAmount = null,
                pricePaidCurrency = null,
                addedAt = null,
            ),
        )

        val pending = store.pending("acct")

        assertEquals(listOf("op-c", "op-r", "op-d"), pending.map { it.opId })
        val create = pending[0] as SyncOperation.Create
        assertEquals(CardVariant.NORMAL, create.item.variant)
        assertEquals(Condition.NEAR_MINT, create.item.condition)
        val replace = pending[1] as SyncOperation.Replace
        assertEquals(3L, replace.baseRevision)
        val delete = pending[2] as SyncOperation.Delete
        assertEquals(2L, delete.baseRevision)
        assertEquals("item-2", delete.itemId)
    }

    @Test
    fun acknowledge_removes_only_the_named_operation() = runTest {
        db.collectionDao().insertSyncOperation(outbox("op-1", "acct", "create", "item-1"))
        db.collectionDao().insertSyncOperation(outbox("op-2", "acct", "create", "item-2"))

        assertTrue(store.acknowledge("acct", "op-1"))
        assertFalse(store.acknowledge("acct", "op-1"))
        assertEquals(listOf("op-2"), store.pending("acct").map { it.opId })
    }

    @Test
    fun apply_remote_create_inserts_item_and_advances_cursor() = runTest {
        val entry = SequencedOperation(
            sequence = 7L,
            operation = SyncOperation.Create("op-7", "device-0001", syncItem("item-1", quantity = 5)),
        )

        assertEquals(RemoteApplyResult.Applied, store.applyRemote("acct", entry))

        val row = db.collectionDao().getById("item-1")
        assertEquals(5, row?.quantity)
        assertEquals(1L, row?.revision)
        assertEquals(7L, store.lastSequence("acct"))
    }

    @Test
    fun apply_remote_replace_bumps_revision_and_stores_price() = runTest {
        db.collectionDao().insertIfAbsent(
            CollectionItemEntity(
                id = "item-1",
                cardId = "base1-4",
                quantity = 2,
                condition = Condition.NEAR_MINT,
                variant = CardVariant.NORMAL,
                pricePaidAmount = null,
                pricePaidCurrency = null,
                addedAt = 1L,
                revision = 4,
            ),
        )
        val entry = SequencedOperation(
            sequence = 9L,
            operation = SyncOperation.Replace(
                "op-9",
                "device-0001",
                baseRevision = 4,
                item = syncItem("item-1", quantity = 6, pricePaid = Money(2.5, Currency.USD)),
            ),
        )

        assertEquals(RemoteApplyResult.Applied, store.applyRemote("acct", entry))

        val row = db.collectionDao().getById("item-1")
        assertEquals(6, row?.quantity)
        assertEquals(5L, row?.revision)
        assertEquals(2.5, row?.pricePaidAmount)
        assertEquals("USD", row?.pricePaidCurrency)
        assertEquals(9L, store.lastSequence("acct"))
    }

    @Test
    fun apply_remote_delete_removes_row_and_advances_cursor() = runTest {
        db.collectionDao().insertIfAbsent(
            CollectionItemEntity(
                id = "item-1",
                cardId = "base1-4",
                quantity = 2,
                condition = Condition.NEAR_MINT,
                variant = CardVariant.NORMAL,
                pricePaidAmount = null,
                pricePaidCurrency = null,
                addedAt = 1L,
                revision = 2,
            ),
        )
        val entry = SequencedOperation(
            sequence = 11L,
            operation = SyncOperation.Delete("op-11", "device-0001", baseRevision = 2, itemId = "item-1"),
        )

        assertEquals(RemoteApplyResult.Applied, store.applyRemote("acct", entry))

        assertNull(db.collectionDao().getById("item-1"))
        assertEquals(11L, store.lastSequence("acct"))
    }

    @Test
    fun apply_remote_config_collision_fails_without_writing_item_or_cursor() = runTest {
        db.collectionDao().insertIfAbsent(
            CollectionItemEntity(
                id = "local-1",
                cardId = "base1-4",
                quantity = 1,
                condition = Condition.NEAR_MINT,
                variant = CardVariant.NORMAL,
                pricePaidAmount = null,
                pricePaidCurrency = null,
                addedAt = 1L,
                revision = 1,
            ),
        )
        val entry = SequencedOperation(
            sequence = 5L,
            operation = SyncOperation.Create("op-5", "device-0001", syncItem("remote-1")),
        )

        val result = store.applyRemote("acct", entry)
        assertTrue(result is RemoteApplyResult.Conflict)
        assertEquals("duplicate-configuration", (result as RemoteApplyResult.Conflict).detail.reason)
        assertEquals("local-1", result.detail.local?.id)
        val stored = store.conflicts("acct").single()
        assertEquals("remote-1", (stored.remote as SyncOperation.Create).item.id)

        assertNull(db.collectionDao().getById("remote-1"))
        assertEquals(0L, store.lastSequence("acct"))
    }

    @Test
    fun durable_push_conflict_blocks_only_rejected_outbox_and_local_resolution_is_atomic_without_cursor_advance() = runTest {
        db.collectionDao().setSyncBinding(app.tcg.app.data.local.SyncBindingEntity(identity = "acct", deviceId = "device-0001"))
        val localItem = syncItem("local-push-1", cardId = "base1-4", quantity = 2)
        db.collectionDao().insertIfAbsent(app.tcg.app.data.local.CollectionItemEntity(
            id = localItem.id, cardId = localItem.cardId, quantity = localItem.quantity,
            condition = localItem.condition, variant = localItem.variant,
            pricePaidAmount = null, pricePaidCurrency = null, addedAt = localItem.addedAt, revision = 1,
        ))
        db.collectionDao().insertSyncOperation(outbox("rejected-op", "acct", "replace", localItem.id, baseRevision = 1))
        db.collectionDao().insertSyncOperation(outbox("unrelated-op", "acct", "create", "other-item", cardId = "base1-5"))
        val rejected = SyncOperation.Replace("rejected-op", "device-0001", 1L, localItem)
        val conflict = PushConflict(
            id = "server-conflict-1", opId = rejected.opId, itemId = localItem.id, reason = "stale",
            deviceId = "device-0001", rejectedOperation = rejected, cloudItem = null, conflictingItem = null,
            highWater = 44L, resolution = null,
        )

        assertTrue(store.savePushConflict("acct", conflict))
        assertEquals(listOf("unrelated-op"), store.pending("acct").map { it.opId })
        assertEquals(2, db.collectionDao().getById(localItem.id)?.quantity)
        assertEquals(0L, store.lastSequence("acct"))

        val result = Json.parseToJsonElement("""{"kind":"keep-cloud","operationId":null,"itemId":"local-push-1","localItemId":"local-push-1","cloudItem":null,"conflictingItem":null}""").jsonObject
        val receipt = PushResolutionResponse("resolved", conflict.id, PushConflictChoice.KEEP_CLOUD, "receipt-1", result = result)
        assertTrue(store.applyPushConflictResolution("acct", conflict.id, receipt))
        assertNull(db.collectionDao().getById(localItem.id))
        assertEquals(listOf("unrelated-op"), store.pending("acct").map { it.opId })
        assertEquals(0L, store.lastSequence("acct"))
        assertTrue(store.finishPushConflictResolution("acct", conflict.id, "receipt-1"))
        assertTrue(store.conflicts("acct").isEmpty())
        assertEquals(0L, store.lastSequence("acct"))
    }

    @Test
    fun duplicate_configuration_keep_local_adopts_cloud_id_and_replays_after_ack_loss() = runTest {
        db.collectionDao().setSyncBinding(app.tcg.app.data.local.SyncBindingEntity(identity = "acct", deviceId = "device-0001"))
        val localItem = syncItem("local-duplicate", cardId = "base1-5", quantity = 4)
        val rejected = SyncOperation.Replace("rejected-duplicate", "device-0001", 1L, localItem)
        db.collectionDao().insertIfAbsent(app.tcg.app.data.local.CollectionItemEntity(
            id = localItem.id, cardId = localItem.cardId, quantity = localItem.quantity,
            condition = localItem.condition, variant = localItem.variant,
            pricePaidAmount = null, pricePaidCurrency = null, addedAt = localItem.addedAt, revision = 1,
        ))
        db.collectionDao().insertSyncOperation(outbox(rejected.opId, "acct", "replace", rejected.item.id, baseRevision = 1, cardId = "base1-5"))
        db.collectionDao().insertSyncOperation(outbox("unrelated", "acct", "create", "unrelated-item"))
        val conflict = PushConflict(
            id = "duplicate-conflict", opId = rejected.opId, itemId = localItem.id, reason = "duplicate-configuration",
            deviceId = rejected.deviceId, rejectedOperation = rejected, cloudItem = null, conflictingItem = null,
            highWater = 18L, resolution = null,
        )
        assertTrue(store.savePushConflict("acct", conflict))
        val result = Json.parseToJsonElement("""{"kind":"keep-local","operationId":"accepted-op","itemId":"cloud-owner","localItemId":"local-duplicate","cloudItem":null,"revision":3,"operation":{"opId":"accepted-op","kind":"replace","itemId":"cloud-owner","baseRevision":2,"item":{}}}""").jsonObject
        val receipt = PushResolutionResponse("resolved", conflict.id, PushConflictChoice.KEEP_LOCAL, "receipt-2", result = result)

        assertTrue(store.applyPushConflictResolution("acct", conflict.id, receipt))
        assertEquals(4, db.collectionDao().getById("cloud-owner")?.quantity)
        assertNull(db.collectionDao().getById("local-duplicate"))
        assertEquals(listOf("unrelated"), store.pending("acct").map { it.opId })
        assertEquals(0L, store.lastSequence("acct"))

        val reopenedStore = RoomSyncLocalStore(db)
        assertTrue(reopenedStore.applyPushConflictResolution("acct", conflict.id, receipt))
        assertEquals(4, db.collectionDao().getById("cloud-owner")?.quantity)
        assertEquals(listOf("unrelated"), reopenedStore.pending("acct").map { it.opId })
    }

    @Test
    fun keep_local_retries_return_the_same_replacement_operation() = runTest {
        db.collectionDao().setSyncBinding(app.tcg.app.data.local.SyncBindingEntity(identity = "acct", deviceId = "device-0001"))
        db.collectionDao().insertIfAbsent(
            CollectionItemEntity(
                id = "local-1", cardId = "base1-4", quantity = 2, condition = Condition.NEAR_MINT,
                variant = CardVariant.NORMAL, pricePaidAmount = null, pricePaidCurrency = null,
                addedAt = 1L, revision = 3,
            ),
        )
        val remote = SyncOperation.Create("op-remote", "device-0002", syncItem("remote-1", quantity = 7))
        val result = store.applyRemote("acct", SequencedOperation(5L, remote))
        assertTrue(result is RemoteApplyResult.Conflict)
        val conflictId = (result as RemoteApplyResult.Conflict).detail.id

        val first = store.resolveConflict("acct", conflictId, SyncConflictResolution.KeepLocal)
        val retry = store.resolveConflict("acct", conflictId, SyncConflictResolution.KeepLocal)

        assertTrue(first is SyncOperation.Replace)
        assertEquals(first, retry)
        assertEquals("remote-1", (first as SyncOperation.Replace).item.id)
        assertEquals(1L, first.baseRevision)
        assertEquals(0L, store.lastSequence("acct"))
        assertEquals(1, store.pending("acct").size)
    }

    @Test
    fun keep_cloud_replaces_conflicting_local_row_and_advances_cursor() = runTest {
        db.collectionDao().setSyncBinding(app.tcg.app.data.local.SyncBindingEntity(identity = "acct", deviceId = "device-0001"))
        db.collectionDao().insertIfAbsent(
            CollectionItemEntity(
                id = "local-1", cardId = "base1-4", quantity = 2, condition = Condition.NEAR_MINT,
                variant = CardVariant.NORMAL, pricePaidAmount = null, pricePaidCurrency = null,
                addedAt = 1L, revision = 1,
            ),
        )
        val remote = SyncOperation.Create("op-remote", "device-0002", syncItem("remote-1", quantity = 7))
        val conflict = store.applyRemote("acct", SequencedOperation(5L, remote)) as RemoteApplyResult.Conflict

        assertNull(store.resolveConflict("acct", conflict.detail.id, SyncConflictResolution.KeepCloud))

        assertNull(db.collectionDao().getById("local-1"))
        assertEquals(7, db.collectionDao().getById("remote-1")?.quantity)
        assertEquals(5L, store.lastSequence("acct"))
        assertTrue(store.conflicts("acct").isEmpty())
    }

    @Test
    fun remote_create_with_existing_id_and_different_content_is_a_conflict() = runTest {
        db.collectionDao().insertIfAbsent(
            CollectionItemEntity(
                id = "same-remote-id", cardId = "base1-4", quantity = 2, condition = Condition.NEAR_MINT,
                variant = CardVariant.NORMAL, pricePaidAmount = null, pricePaidCurrency = null,
                addedAt = 1L, revision = 3,
            ),
        )
        val operation = SyncOperation.Create("remote-colliding-id", "device-0002", syncItem(
            id = "same-remote-id", cardId = "base1-5", quantity = 9,
        ))

        val result = store.applyRemote("acct", SequencedOperation(8L, operation))

        assertTrue(result is RemoteApplyResult.Conflict)
        assertEquals("id-collision", (result as RemoteApplyResult.Conflict).detail.reason)
        assertEquals("base1-4", db.collectionDao().getById("same-remote-id")?.cardId)
        assertEquals(2, db.collectionDao().getById("same-remote-id")?.quantity)
        assertEquals(0L, store.lastSequence("acct"))
    }

    @Test
    fun cursor_is_monotonic_and_never_regresses() = runTest {
        store.saveSequence("acct", 10L)
        assertEquals(10L, store.lastSequence("acct"))

        store.saveSequence("acct", 4L)
        assertEquals(10L, store.lastSequence("acct"))
    }

    @Test
    fun complete_link_imports_remote_rows_rebases_revisions_and_sets_binding_atomically() = runTest {
        val dao = db.collectionDao()
        dao.upsertRemoteItem(
            CollectionItemEntity(
                id = "item-local", cardId = "base1-4", quantity = 1, condition = Condition.NEAR_MINT,
                variant = CardVariant.NORMAL, pricePaidAmount = null, pricePaidCurrency = null,
                addedAt = 1_791_667_200_000L, revision = 1,
            ),
        )
        db.cardDao().upsert(
            app.tcg.app.data.local.CardEntity(
                id = "base2-3", name = "Blastoise", setId = "base2", setName = "Jungle",
                collectorNumber = "3", rarity = null, hp = null, imageUrl = null, types = emptyList(),
                attacks = emptyList(), variants = setOf(CardVariant.NORMAL), setTotal = 64,
            ),
        )

        val linked = store.completeLink(
            LinkCompletion(
                identity = "acct",
                deviceId = "device-0001",
                expectedItems = listOf(syncItem("item-local", quantity = 1)),
                remoteItems = listOf(
                    RemoteLinkItem(syncItem("item-local", quantity = 1), revision = 4),
                    RemoteLinkItem(syncItem("item-remote", cardId = "base2-3", quantity = 3), revision = 2),
                ),
                seedRevisions = mapOf("item-local" to 4L),
                highWater = 9L,
            ),
        )

        assertTrue(linked)
        assertEquals(4L, dao.getById("item-local")?.revision)
        assertEquals(2L, dao.getById("item-remote")?.revision)
        assertEquals(3, dao.getById("item-remote")?.quantity)
        val binding = dao.getSyncBinding()
        assertEquals("acct", binding?.identity)
        assertEquals("device-0001", binding?.deviceId)
        assertEquals(9L, store.lastSequence("acct"))
    }

    @Test
    fun complete_link_seeds_revisions_for_pushed_local_only_items() = runTest {
        val dao = db.collectionDao()
        dao.upsertRemoteItem(
            CollectionItemEntity(
                id = "item-local", cardId = "base1-4", quantity = 2, condition = Condition.NEAR_MINT,
                variant = CardVariant.NORMAL, pricePaidAmount = null, pricePaidCurrency = null,
                addedAt = 1_791_667_200_000L, revision = 1,
            ),
        )

        val linked = store.completeLink(
            LinkCompletion(
                identity = "acct",
                deviceId = "device-0001",
                expectedItems = listOf(syncItem("item-local", quantity = 2)),
                remoteItems = emptyList(),
                seedRevisions = mapOf("item-local" to 1L),
                highWater = 3L,
            ),
        )

        assertTrue(linked)
        assertEquals(1L, dao.getById("item-local")?.revision)
        assertEquals("acct", dao.getSyncBinding()?.identity)
    }

    @Test
    fun complete_link_refuses_a_changed_local_snapshot_without_mutating_anything() = runTest {
        val dao = db.collectionDao()
        dao.upsertRemoteItem(
            CollectionItemEntity(
                id = "item-local", cardId = "base1-4", quantity = 5, condition = Condition.NEAR_MINT,
                variant = CardVariant.NORMAL, pricePaidAmount = null, pricePaidCurrency = null,
                addedAt = 1_791_667_200_000L, revision = 2,
            ),
        )

        val linked = store.completeLink(
            LinkCompletion(
                identity = "acct",
                deviceId = "device-0001",
                expectedItems = listOf(syncItem("item-local", quantity = 1)),
                remoteItems = emptyList(),
                seedRevisions = mapOf("item-local" to 1L),
                highWater = 3L,
            ),
        )

        assertFalse(linked)
        assertEquals(5, dao.getById("item-local")?.quantity)
        assertEquals(2L, dao.getById("item-local")?.revision)
        assertNull(dao.getSyncBinding())
        assertEquals(0L, store.lastSequence("acct"))
    }

    @Test
    fun complete_link_refuses_a_remote_row_whose_card_is_not_resolvable() = runTest {
        val dao = db.collectionDao()

        val linked = store.completeLink(
            LinkCompletion(
                identity = "acct",
                deviceId = "device-0001",
                expectedItems = emptyList(),
                remoteItems = listOf(RemoteLinkItem(syncItem("item-remote", cardId = "base9-9"), revision = 1)),
                seedRevisions = emptyMap(),
                highWater = 1L,
            ),
        )

        assertFalse(linked)
        assertNull(dao.getById("item-remote"))
        assertNull(dao.getSyncBinding())
    }

    @Test
    fun complete_link_refuses_a_configuration_collision_and_keeps_both_sides() = runTest {
        val dao = db.collectionDao()
        dao.upsertRemoteItem(
            CollectionItemEntity(
                id = "item-local", cardId = "base1-4", quantity = 1, condition = Condition.NEAR_MINT,
                variant = CardVariant.NORMAL, pricePaidAmount = null, pricePaidCurrency = null,
                addedAt = 1_791_667_200_000L, revision = 1,
            ),
        )

        val linked = store.completeLink(
            LinkCompletion(
                identity = "acct",
                deviceId = "device-0001",
                expectedItems = listOf(syncItem("item-local", quantity = 1)),
                remoteItems = listOf(
                    RemoteLinkItem(syncItem("item-remote", quantity = 2), revision = 1),
                ),
                seedRevisions = mapOf("item-local" to 1L),
                highWater = 1L,
            ),
        )

        assertFalse(linked)
        assertNull(dao.getById("item-remote"))
        assertEquals(1L, dao.getById("item-local")?.revision)
        assertNull(dao.getSyncBinding())
    }

    @Test
    fun complete_link_requires_a_seed_revision_for_every_pushed_local_only_item() = runTest {
        val dao = db.collectionDao()
        dao.upsertRemoteItem(
            CollectionItemEntity(
                id = "item-local", cardId = "base1-4", quantity = 1, condition = Condition.NEAR_MINT,
                variant = CardVariant.NORMAL, pricePaidAmount = null, pricePaidCurrency = null,
                addedAt = 1_791_667_200_000L, revision = 1,
            ),
        )

        val linked = store.completeLink(
            LinkCompletion(
                identity = "acct",
                deviceId = "device-0001",
                expectedItems = listOf(syncItem("item-local", quantity = 1)),
                remoteItems = emptyList(),
                seedRevisions = emptyMap(),
                highWater = 1L,
            ),
        )

        assertFalse(linked)
        assertNull(dao.getSyncBinding())
    }

    @Test
    fun cursor_is_scoped_per_identity() = runTest {
        store.saveSequence("acct-a", 3L)
        store.saveSequence("acct-b", 8L)

        assertEquals(3L, store.lastSequence("acct-a"))
        assertEquals(8L, store.lastSequence("acct-b"))
    }
}
