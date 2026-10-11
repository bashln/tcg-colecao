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

        assertTrue(store.applyRemote("acct", entry))

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

        assertTrue(store.applyRemote("acct", entry))

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

        assertTrue(store.applyRemote("acct", entry))

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

        assertFalse(store.applyRemote("acct", entry))

        assertNull(db.collectionDao().getById("remote-1"))
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
    fun cursor_is_scoped_per_identity() = runTest {
        store.saveSequence("acct-a", 3L)
        store.saveSequence("acct-b", 8L)

        assertEquals(3L, store.lastSequence("acct-a"))
        assertEquals(8L, store.lastSequence("acct-b"))
    }
}
