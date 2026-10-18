package app.tcg.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tcg.app.data.local.CardEntity
import app.tcg.app.data.local.CollectionDao
import app.tcg.app.data.local.CollectionItemEntity
import app.tcg.app.data.local.TcgDatabase
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class FakeBackupDocumentStore : BackupDocumentStore {
    val documents = mutableMapOf<String, ByteArray>()
    var failWrite = false
    var failRead = false
    var failDelete = false
    var corruptRead = false

    override suspend fun writeDocument(treeUri: String, name: String, bytes: ByteArray): String? {
        if (failWrite) return null
        val docUri = "$treeUri/$name"
        documents[docUri] = bytes
        return docUri
    }

    override suspend fun readDocument(documentUri: String): ByteArray? {
        if (failRead) return null
        val data = documents[documentUri] ?: return null
        return if (corruptRead) data + byteArrayOf(0x01) else data
    }

    override suspend fun deleteDocument(documentUri: String): Boolean {
        if (failDelete) return false
        return documents.remove(documentUri) != null
    }
}

@RunWith(RobolectricTestRunner::class)
class LocalBackupManagerTest {
    private lateinit var db: TcgDatabase
    private lateinit var dao: CollectionDao
    private lateinit var store: FakeBackupDocumentStore
    private lateinit var manager: LocalBackupManager

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
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertCardAndItem(cardId: String = "base1-4", quantity: Int = 2) {
        val card = CardEntity(
            id = cardId,
            name = "Charizard",
            setId = "base1",
            setName = "Base Set",
            collectorNumber = "4",
            rarity = "Rare Holo",
            hp = 120,
            imageUrl = null,
            types = listOf("Fire"),
            attacks = emptyList(),
            variants = setOf(CardVariant.NORMAL),
            setTotal = 102,
        )
        dao.upsertCard(card)
        val item = CollectionItemEntity(
            id = "item-$cardId",
            cardId = cardId,
            quantity = quantity,
            condition = Condition.NEAR_MINT,
            variant = CardVariant.NORMAL,
            pricePaidAmount = null,
            pricePaidCurrency = null,
            addedAt = 1000L,
            revision = 1L,
        )
        dao.insertIfAbsent(item)
    }

    @Test
    fun first_backup_creates_3_validated_generations_and_marks_saved() = runBlocking {
        insertCardAndItem()
        dao.setBackupFolder("content://tree/backup")

        val state = dao.getBackupState()
        assertNotNull(state)
        assertTrue(state!!.pendingRevision > 0)

        val result = manager.runBackup()
        assertTrue(result is BackupExecutionResult.Success)
        val success = result as BackupExecutionResult.Success
        assertEquals(state.pendingRevision, success.savedRevision)
        assertEquals(3, success.totalGenerations)

        val generations = dao.listGenerations("content://tree/backup")
        assertEquals(3, generations.size)
        assertEquals(3, store.documents.size)

        val updatedState = dao.getBackupState()!!
        assertEquals(state.pendingRevision, updatedState.savedRevision)
        assertNull(updatedState.failure)
    }

    @Test
    fun subsequent_backup_creates_1_generation_and_retains_3_newest() = runBlocking {
        insertCardAndItem()
        dao.setBackupFolder("content://tree/backup")
        manager.runBackup()

        val item = dao.getById("item-base1-4")!!
        dao.updateEditableFields(
            itemId = item.id,
            quantity = 5,
            variant = item.variant,
            condition = item.condition,
            pricePaidAmount = null,
            pricePaidCurrency = null,
        )

        val stateAfterEdit = dao.getBackupState()!!
        assertTrue(stateAfterEdit.pendingRevision > stateAfterEdit.savedRevision)

        val result2 = manager.runBackup()
        assertTrue(result2 is BackupExecutionResult.Success)

        val generations = dao.listGenerations("content://tree/backup")
        assertEquals(3, generations.size)
        assertEquals(3, store.documents.size)
        assertEquals(stateAfterEdit.pendingRevision, dao.getBackupState()!!.savedRevision)
    }

    @Test
    fun reopen_read_failure_aborts_and_keeps_pending() = runBlocking {
        insertCardAndItem()
        dao.setBackupFolder("content://tree/backup")
        val stateBefore = dao.getBackupState()!!

        store.failRead = true
        val result = manager.runBackup()
        assertTrue(result is BackupExecutionResult.Failure)

        val stateAfter = dao.getBackupState()!!
        assertEquals(stateBefore.pendingRevision, stateAfter.pendingRevision)
        assertEquals(stateBefore.savedRevision, stateAfter.savedRevision)
        assertNotNull(stateAfter.failure)
    }

    @Test
    fun digest_mismatch_aborts_and_keeps_pending() = runBlocking {
        insertCardAndItem()
        dao.setBackupFolder("content://tree/backup")

        store.corruptRead = true
        val result = manager.runBackup()
        assertTrue(result is BackupExecutionResult.Failure)
        assertEquals("Divergência de integridade (SHA-256)", (result as BackupExecutionResult.Failure).reason)

        val stateAfter = dao.getBackupState()!!
        assertEquals(-1L, stateAfter.savedRevision)
        assertEquals("Divergência de integridade (SHA-256)", stateAfter.failure)
    }

    @Test
    fun safe_retention_when_delete_fails_records_warning_without_failing_backup() = runBlocking {
        insertCardAndItem()
        dao.setBackupFolder("content://tree/backup")
        manager.runBackup()

        store.failDelete = true
        dao.addQuantity("base1-4", CardVariant.NORMAL, Condition.NEAR_MINT, 1)

        val result = manager.runBackup()
        assertTrue(result is BackupExecutionResult.Success)

        val state = dao.getBackupState()!!
        assertNotNull(state.retentionWarning)
        assertTrue(state.retentionWarning!!.contains("Falha ao remover cópia antiga"))
    }
}
