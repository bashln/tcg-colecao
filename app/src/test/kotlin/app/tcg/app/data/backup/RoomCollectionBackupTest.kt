package app.tcg.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tcg.app.data.local.CardEntity
import app.tcg.app.data.local.CollectionDao
import app.tcg.app.data.local.CollectionItemEntity
import app.tcg.app.data.local.SyncBindingEntity
import app.tcg.app.data.local.TcgDatabase
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class FakeRecoveryStore : RecoveryStore {
    var shouldFail = false
    var lastSavedEntries: List<WebEntry>? = null

    override suspend fun writeAndVerifyRecovery(entries: List<WebEntry>): Boolean {
        if (shouldFail) return false
        lastSavedEntries = entries
        return true
    }
}

@RunWith(RobolectricTestRunner::class)
class RoomCollectionBackupTest {
    private lateinit var db: TcgDatabase
    private lateinit var dao: CollectionDao
    private lateinit var store: FakeBackupDocumentStore
    private lateinit var manager: LocalBackupManager
    private lateinit var recoveryStore: FakeRecoveryStore
    private lateinit var backup: RoomCollectionBackup

    private val validBackupJson = """
        {
          "format": "tcg-colecao-web-1",
          "exportedAt": "2026-10-18T10:00:00Z",
          "entries": [
            {
              "item": {
                "id": "item-import-1",
                "cardId": "base1-4",
                "quantity": 2,
                "variant": "NORMAL",
                "condition": "NEAR_MINT",
                "pricePaid": null,
                "addedAt": 1000,
                "revision": 1,
                "configurationKey": "base1-4\u001fNORMAL\u001fNEAR_MINT"
              },
              "card": {
                "id": "base1-4",
                "name": "Charizard",
                "setId": "base1",
                "setName": "Base Set",
                "collectorNumber": "4",
                "imageUrl": null,
                "variants": ["NORMAL"],
                "prices": [],
                "setTotal": 102
              }
            }
          ]
        }
    """.trimIndent()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TcgDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(TcgDatabase.BACKUP_TRIGGERS)
            .build()
        dao = db.collectionDao()
        store = FakeBackupDocumentStore()
        manager = LocalBackupManager(dao, store)
        recoveryStore = FakeRecoveryStore()
        backup = RoomCollectionBackup(dao, recoveryStore, manager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun trigger_increments_pending_revision_on_collection_mutations() = runBlocking {
        val card = CardEntity(
            id = "base1-4",
            name = "Charizard",
            setId = "base1",
            setName = "Base Set",
            collectorNumber = "4",
            rarity = null,
            hp = null,
            imageUrl = null,
            types = emptyList(),
            attacks = emptyList(),
            variants = setOf(CardVariant.NORMAL),
            setTotal = 102,
        )
        dao.upsertCard(card)
        val state0 = dao.getBackupState()!!
        assertEquals(0L, state0.pendingRevision)

        val item = CollectionItemEntity(
            id = "item-1",
            cardId = "base1-4",
            quantity = 1,
            condition = Condition.NEAR_MINT,
            variant = CardVariant.NORMAL,
            pricePaidAmount = null,
            pricePaidCurrency = null,
            addedAt = 1000L,
            revision = 1L,
        )
        dao.insertIfAbsent(item)
        val state1 = dao.getBackupState()!!
        assertEquals(1L, state1.pendingRevision)

        dao.updateEditableFields(item.id, 3, item.variant, item.condition, null, null)
        val state2 = dao.getBackupState()!!
        assertEquals(2L, state2.pendingRevision)

        dao.deleteById(item.id)
        val state3 = dao.getBackupState()!!
        assertEquals(3L, state3.pendingRevision)
    }

    @Test
    fun confirm_import_applies_cards_items_markers_and_outbox_atomically() = runBlocking {
        dao.setSyncBinding(SyncBindingEntity(slot = 0, identity = "user-1", deviceId = "dev-1"))

        val preview = backup.previewImport(validBackupJson.toByteArray())
        assertTrue(preview is ImportPreview.Ready)
        val ticket = (preview as ImportPreview.Ready).ticket
        assertEquals(1, ticket.summary.added)
        assertEquals(0, ticket.summary.merged)
        assertEquals(0, ticket.summary.unchanged)

        val result = backup.confirmImport(ticket)
        assertTrue(result is ImportResult.Success)

        val items = dao.listItems()
        assertEquals(1, items.size)
        assertEquals("item-import-1", items.first().id)
        assertEquals(2, items.first().quantity)

        val card = dao.listAllCards().firstOrNull { it.id == "base1-4" }
        assertNotNull(card)
        assertEquals("Charizard", card!!.name)

        val markers = dao.listImportMarkers()
        assertEquals(1, markers.size)
        assertEquals("item-import-1", markers.first().itemId)

        val outbox = dao.listPendingOperations("user-1")
        assertEquals(1, outbox.size)
        assertEquals("create", outbox.first().kind)
        assertEquals("item-import-1", outbox.first().itemId)
    }

    @Test
    fun repeated_import_is_idempotent() = runBlocking {
        val preview1 = backup.previewImport(validBackupJson.toByteArray())
        val ticket1 = (preview1 as ImportPreview.Ready).ticket
        backup.confirmImport(ticket1)

        val preview2 = backup.previewImport(validBackupJson.toByteArray())
        assertTrue(preview2 is ImportPreview.Ready)
        val ticket2 = (preview2 as ImportPreview.Ready).ticket
        assertEquals(0, ticket2.summary.added)
        assertEquals(0, ticket2.summary.merged)
        assertEquals(1, ticket2.summary.unchanged)

        val result2 = backup.confirmImport(ticket2)
        assertTrue(result2 is ImportResult.Success)

        val items = dao.listItems()
        assertEquals(1, items.size)
        assertEquals(2, items.first().quantity)
    }

    @Test
    fun stale_preview_refusal_when_collection_changes_after_preview() = runBlocking {
        val card = CardEntity(
            id = "base1-4",
            name = "Charizard",
            setId = "base1",
            setName = "Base Set",
            collectorNumber = "4",
            rarity = null,
            hp = null,
            imageUrl = null,
            types = emptyList(),
            attacks = emptyList(),
            variants = setOf(CardVariant.NORMAL),
            setTotal = 102,
        )
        dao.upsertCard(card)
        val existingItem = CollectionItemEntity(
            id = "local-1",
            cardId = "base1-4",
            quantity = 1,
            condition = Condition.NEAR_MINT,
            variant = CardVariant.NORMAL,
            pricePaidAmount = null,
            pricePaidCurrency = null,
            addedAt = 500L,
            revision = 1L,
        )
        dao.insertIfAbsent(existingItem)

        val preview = backup.previewImport(validBackupJson.toByteArray())
        assertTrue(preview is ImportPreview.Ready)
        val ticket = (preview as ImportPreview.Ready).ticket

        dao.updateEditableFields("local-1", 10, CardVariant.NORMAL, Condition.NEAR_MINT, null, null)

        val result = backup.confirmImport(ticket)
        assertEquals(ImportResult.ChangedSincePreview, result)

        val itemAfter = dao.getById("local-1")!!
        assertEquals(10, itemAfter.quantity)
        assertEquals(0, dao.listImportMarkers().size)
    }

    @Test
    fun recovery_failure_aborts_import_without_mutating_room() = runBlocking {
        val preview = backup.previewImport(validBackupJson.toByteArray())
        val ticket = (preview as ImportPreview.Ready).ticket

        recoveryStore.shouldFail = true
        val result = backup.confirmImport(ticket)
        assertTrue(result is ImportResult.StorageError)

        assertEquals(0, dao.listItems().size)
        assertEquals(0, dao.listImportMarkers().size)
    }
}
