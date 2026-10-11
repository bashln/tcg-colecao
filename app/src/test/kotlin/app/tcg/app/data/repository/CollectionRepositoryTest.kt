package app.tcg.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tcg.app.data.local.TcgDatabase
import app.tcg.app.data.local.CollectionItemEntity
import app.tcg.app.data.local.CardPriceObservationEntity
import app.tcg.app.data.local.SyncBindingEntity
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.CollectionItemChanges
import app.tcg.core.domain.model.CollectionUpdate
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.valuation.Valuation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CollectionRepositoryTest {
    private lateinit var db: TcgDatabase
    private lateinit var repo: RoomCollectionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TcgDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomCollectionRepository(db.collectionDao(), db.cardDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun add_persists_item_with_price_and_resolves_card_in_list() = runTest {
        val item = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, Money(2.0, Currency.USD))

        assertEquals("swsh3-136", item.cardId)
        assertEquals(1, item.quantity)
        assertEquals(2.0, item.pricePaid!!.amount, 0.0)
        assertEquals(Currency.USD, item.pricePaid!!.currency)

        val entries = repo.list()
        assertEquals(1, entries.size)
        assertEquals(item.id, entries.first().item.id)
        assertEquals("Furret", entries.first().card.name)
        assertEquals("swsh3", entries.first().card.setId)
    }

    @Test
    fun linked_add_and_quantity_merge_queue_ordered_create_and_replace_operations() = runTest {
        db.collectionDao().setSyncBinding(SyncBindingEntity(identity = "user-a", deviceId = "device-a"))

        val created = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, Money(2.0, Currency.USD))
        val merged = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 2, Money(9.0, Currency.USD))
        val operations = db.collectionDao().listPendingOperations("user-a")

        assertEquals(3, merged.quantity)
        assertEquals(1L, operations[0].sequence)
        assertEquals("create", operations[0].kind)
        assertNull(operations[0].baseRevision)
        assertEquals(created.id, operations[0].itemId)
        assertEquals(1L, operations[0].revision)
        assertEquals("replace", operations[1].kind)
        assertEquals(1L, operations[1].baseRevision)
        assertEquals(2L, operations[1].revision)
        assertEquals(3, operations[1].quantity)
        assertEquals(2.0, operations[1].pricePaidAmount!!, 0.0)
    }

    @Test
    fun linked_edit_and_remove_queue_replacement_then_tombstone() = runTest {
        db.collectionDao().setSyncBinding(SyncBindingEntity(identity = "user-a", deviceId = "device-a"))
        val original = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, null)
        val loaded = repo.getById(original.id)!!.item

        val updated = repo.update(
            loaded,
            CollectionItemChanges(3, CardVariant.NORMAL, Condition.LIGHTLY_PLAYED, Money(4.5, Currency.EUR)),
        ) as CollectionUpdate.Updated
        repo.remove(updated.item.id)

        val operations = db.collectionDao().listPendingOperations("user-a")
        assertEquals(listOf("create", "replace", "delete"), operations.map { it.kind })
        assertEquals(1L, operations[1].baseRevision)
        assertEquals(2L, operations[1].revision)
        assertEquals(3, operations[1].quantity)
        assertEquals(2L, operations[2].baseRevision)
        assertEquals(updated.item.id, operations[2].itemId)
        assertTrue(repo.list().isEmpty())
    }

    @Test
    fun unlinked_mutations_remain_local_and_do_not_create_sync_operations() = runTest {
        val item = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, null)

        repo.update(item, CollectionItemChanges(2, CardVariant.NORMAL, Condition.NEAR_MINT, null))
        repo.remove(item.id)

        assertTrue(repo.list().isEmpty())
        assertTrue(db.collectionDao().listPendingOperations("user-a").isEmpty())
    }

    @Test
    fun acknowledging_operation_requires_matching_identity_and_preserves_other_rows() = runTest {
        db.collectionDao().setSyncBinding(SyncBindingEntity(identity = "user-a", deviceId = "device-a"))
        repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, null)
        val operation = db.collectionDao().listPendingOperations("user-a").single()

        assertEquals(0, db.collectionDao().acknowledgeOperation(operation.opId, "user-b"))
        assertEquals(1, db.collectionDao().listPendingOperations("user-a").size)
        assertEquals(1, db.collectionDao().acknowledgeOperation(operation.opId, "user-a"))
        assertTrue(db.collectionDao().listPendingOperations("user-a").isEmpty())
    }

    @Test
    fun outbox_insert_failure_rolls_back_collection_add() = runTest {
        db.collectionDao().setSyncBinding(SyncBindingEntity(identity = "user-a", deviceId = "device-a"))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_sync_outbox BEFORE INSERT ON sync_outbox BEGIN SELECT RAISE(ABORT, 'forced outbox failure'); END",
        )

        val result = runCatching { repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, null) }

        assertTrue(result.isFailure)
        assertTrue(repo.list().isEmpty())
        assertTrue(db.collectionDao().listPendingOperations("user-a").isEmpty())
    }

    @Test
    fun outbox_insert_failure_rolls_back_edit_and_remove() = runTest {
        db.collectionDao().setSyncBinding(SyncBindingEntity(identity = "user-a", deviceId = "device-a"))
        val created = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, null)
        val beforeEdit = repo.getById(created.id)!!.item
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_sync_outbox BEFORE INSERT ON sync_outbox BEGIN SELECT RAISE(ABORT, 'forced outbox failure'); END",
        )

        val edit = runCatching {
            repo.update(beforeEdit, CollectionItemChanges(3, CardVariant.NORMAL, Condition.LIGHTLY_PLAYED, null))
        }
        val removal = runCatching { repo.remove(created.id) }

        assertTrue(edit.isFailure)
        assertTrue(removal.isFailure)
        assertEquals(beforeEdit, repo.getById(created.id)!!.item)
        assertEquals(listOf("create"), db.collectionDao().listPendingOperations("user-a").map { it.kind })
    }

    @Test
    fun linked_edit_and_delete_outbox_survive_file_backed_database_reopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "sync-reopen-${UUID.randomUUID()}.db"
        var persistentDb = Room.databaseBuilder(context, TcgDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        var persistentRepo = RoomCollectionRepository(persistentDb.collectionDao(), persistentDb.cardDao())
        persistentDb.collectionDao().setSyncBinding(SyncBindingEntity(identity = "user-a", deviceId = "device-a"))
        val created = persistentRepo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, null)
        val loaded = persistentRepo.getById(created.id)!!.item
        val updated = (persistentRepo.update(
            loaded,
            CollectionItemChanges(4, CardVariant.NORMAL, Condition.LIGHTLY_PLAYED, Money(3.25, Currency.USD)),
        ) as CollectionUpdate.Updated).item
        persistentDb.close()

        persistentDb = Room.databaseBuilder(context, TcgDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        persistentRepo = RoomCollectionRepository(persistentDb.collectionDao(), persistentDb.cardDao())
        val reopenedItem = persistentRepo.getById(created.id)!!.item
        val beforeDelete = persistentDb.collectionDao().listPendingOperations("user-a")
        assertEquals(updated, reopenedItem)
        assertEquals(listOf("create", "replace"), beforeDelete.map { it.kind })
        assertEquals(1L, beforeDelete[1].baseRevision)
        assertEquals(4, beforeDelete[1].quantity)
        persistentRepo.remove(created.id)
        persistentDb.close()

        persistentDb = Room.databaseBuilder(context, TcgDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        persistentRepo = RoomCollectionRepository(persistentDb.collectionDao(), persistentDb.cardDao())
        assertNull(persistentRepo.getById(created.id))
        val afterDelete = persistentDb.collectionDao().listPendingOperations("user-a")
        assertEquals(listOf("create", "replace", "delete"), afterDelete.map { it.kind })
        assertEquals(2L, afterDelete[2].baseRevision)
        persistentDb.close()
    }

    @Test
    fun add_records_the_supplied_card_market_prices() = runTest {
        repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, null)

        val observations = db.cardDao().priceObservationsFor(
            "swsh3-136",
            PriceSource.TCGPLAYER,
            CardVariant.NORMAL,
            Currency.USD,
        )

        assertEquals(1, observations.size)
        assertEquals(0.27, observations.single().amount, 0.0)
    }

    @Test
    fun history_query_preserves_unpriced_holdings_and_includes_a_later_capture() = runTest {
        val day = LocalDate.parse("2026-01-10")
        repo.add(sampleCard(), CardVariant.REVERSE_HOLOFOIL, Condition.NEAR_MINT, 2, null)
        val before = repo.observeCurrentHoldingsHistory(PriceSource.TCGPLAYER, day).first()
        assertEquals(2, before.totalUnits)
        assertTrue(before.recorded.isEmpty())

        db.cardDao().upsertPriceObservations(
            listOf(CardPriceObservationEntity(
                cardId = "swsh3-136",
                source = PriceSource.TCGPLAYER,
                variant = CardVariant.REVERSE_HOLOFOIL,
                currency = Currency.USD,
                amount = 1.25,
                observedAt = 1768003200000L,
                utcDay = day,
            )),
        )
        val after = repo.observeCurrentHoldingsHistory(PriceSource.TCGPLAYER, day).first()
        assertEquals(Money(2.5, Currency.USD), after.latest?.value)
        assertEquals(2, after.latest?.coverage?.pricedUnits)
        assertEquals(0, after.latest?.coverage?.missingUnits)
    }

    @Test
    fun second_add_of_same_card_and_variant_sums_quantity_and_keeps_first_price() = runTest {
        repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, Money(2.0, Currency.USD))
        val second = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 2, Money(9.0, Currency.USD))

        assertEquals(3, second.quantity)
        assertEquals(2.0, second.pricePaid!!.amount, 0.0)
        assertEquals(1, repo.list().size)
    }

    @Test
    fun list_resolves_prices_so_valuation_works_offline() = runTest {
        repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, null)

        val entry = repo.list().single()
        assertEquals(0.27, Valuation.itemValue(entry.card, CardVariant.NORMAL, PriceSource.TCGPLAYER)!!.amount, 0.0)
        assertEquals(0.07, Valuation.itemValue(entry.card, CardVariant.NORMAL, PriceSource.CARDMARKET)!!.amount, 0.0)
    }

    @Test
    fun add_without_price_persists_null_price_paid() = runTest {
        val item = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, null)
        assertNull(item.pricePaid)
        assertNull(repo.list().single().item.pricePaid)
    }

    @Test
    fun update_revises_exact_item_and_preserves_its_identity_date_and_price() = runTest {
        val original = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 2, Money(2.0, Currency.USD))
        val details = repo.getById(original.id)!!

        val result = repo.update(
            details.item,
            CollectionItemChanges(4, CardVariant.REVERSE_HOLOFOIL, Condition.LIGHTLY_PLAYED, Money(5.5, Currency.EUR)),
        )

        val updated = (result as CollectionUpdate.Updated).item
        assertEquals(original.id, updated.id)
        assertEquals(original.cardId, updated.cardId)
        assertEquals(original.addedAt, updated.addedAt)
        assertEquals(4, updated.quantity)
        assertEquals(CardVariant.REVERSE_HOLOFOIL, updated.variant)
        assertEquals(Condition.LIGHTLY_PLAYED, updated.condition)
        assertEquals(Money(5.5, Currency.EUR), updated.pricePaid)
        assertEquals(updated, repo.getById(original.id)!!.item)
    }

    @Test
    fun update_refuses_an_existing_card_variant_condition_without_mutating_either_item() = runTest {
        val normal = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 2, Money(2.0, Currency.USD))
        val reverse = repo.add(sampleCard(), CardVariant.REVERSE_HOLOFOIL, Condition.NEAR_MINT, 3, Money(7.0, Currency.USD))
        val loaded = repo.getById(normal.id)!!.item

        val result = repo.update(
            loaded,
            CollectionItemChanges(8, CardVariant.REVERSE_HOLOFOIL, Condition.NEAR_MINT, Money(9.0, Currency.USD)),
        )

        assertEquals(CollectionUpdate.DuplicateConfiguration, result)
        val entries = repo.list().associateBy { it.item.id }
        assertEquals(2, entries.size)
        assertEquals(2, entries.getValue(normal.id).item.quantity)
        assertEquals(Money(2.0, Currency.USD), entries.getValue(normal.id).item.pricePaid)
        assertEquals(3, entries.getValue(reverse.id).item.quantity)
        assertEquals(Money(7.0, Currency.USD), entries.getValue(reverse.id).item.pricePaid)
    }

    @Test
    fun update_refuses_a_stale_snapshot_after_an_add_and_can_retry_idempotently() = runTest {
        val original = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, null)
        val loaded = repo.getById(original.id)!!.item
        repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 2, null)
        val changes = CollectionItemChanges(5, CardVariant.NORMAL, Condition.LIGHTLY_PLAYED, null)

        assertEquals(CollectionUpdate.ChangedSinceLoaded, repo.update(loaded, changes))
        assertEquals(3, repo.getById(original.id)!!.item.quantity)

        val refreshed = repo.getById(original.id)!!.item
        val first = repo.update(refreshed, changes) as CollectionUpdate.Updated
        val retry = repo.update(refreshed, changes) as CollectionUpdate.Updated
        assertEquals(5, first.item.quantity)
        assertEquals(first.item, retry.item)
    }

    @Test
    fun get_by_id_distinguishes_removed_item_and_update_never_recreates_it() = runTest {
        val item = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, null)
        val loaded = repo.getById(item.id)!!.item
        repo.remove(item.id)

        assertNull(repo.getById(item.id))
        assertEquals(
            CollectionUpdate.NotFound,
            repo.update(loaded, CollectionItemChanges(2, CardVariant.NORMAL, Condition.NEAR_MINT, null)),
        )
        assertTrue(repo.list().isEmpty())
    }

    @Test
    fun get_by_id_keeps_an_orphaned_saved_item_editable_without_card_metadata() = runTest {
        db.collectionDao().insertIfAbsent(
            CollectionItemEntity(
                id = "orphaned-item",
                cardId = "card-missing-from-cache",
                quantity = 2,
                condition = Condition.NEAR_MINT,
                variant = CardVariant.NORMAL,
                pricePaidAmount = null,
                pricePaidCurrency = null,
                addedAt = 10L,
            ),
        )

        val details = repo.getById("orphaned-item")

        assertEquals("orphaned-item", details?.item?.id)
        assertNull(details?.card)
    }

    @Test
    fun update_can_explicitly_clear_price_paid() = runTest {
        val original = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, Money(2.0, Currency.USD))
        val loaded = repo.getById(original.id)!!.item

        val result = repo.update(
            loaded,
            CollectionItemChanges(1, CardVariant.NORMAL, Condition.NEAR_MINT, null),
        ) as CollectionUpdate.Updated

        assertNull(result.item.pricePaid)
        assertNull(repo.getById(original.id)?.item?.pricePaid)
    }

    @Test
    fun remove_deletes_the_item() = runTest {
        val item = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, null)

        repo.remove(item.id)

        assertTrue(repo.list().isEmpty())
    }

    private fun sampleCard() = Card(
        id = "swsh3-136",
        name = "Furret",
        setId = "swsh3",
        setName = "Escuridão Incandescente",
        collectorNumber = "136",
        rarity = "Incomum",
        hp = 110,
        types = listOf("Incolor"),
        attacks = emptyList(),
        imageUrl = "https://assets.tcgdex.net/pt/swsh/swsh3/136/high.webp",
        variants = setOf(CardVariant.NORMAL, CardVariant.REVERSE_HOLOFOIL),
        prices = listOf(
            price(PriceSource.TCGPLAYER, Currency.USD, 0.27),
            price(PriceSource.CARDMARKET, Currency.EUR, 0.07),
        ),
    )

    private fun price(source: PriceSource, currency: Currency, market: Double) = CardPrice(
        source = source,
        variant = CardVariant.NORMAL,
        currency = currency,
        market = market,
        low = null, mid = null, high = null, trend = null,
        avg1 = null, avg7 = null, avg30 = null, updatedAt = null,
    )
}
