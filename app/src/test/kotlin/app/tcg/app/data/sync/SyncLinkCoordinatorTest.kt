package app.tcg.app.data.sync

import app.tcg.app.data.local.CardDao
import app.tcg.app.data.local.CollectionDao
import app.tcg.app.data.local.CollectionItemEntity
import app.tcg.app.data.local.TcgDatabase
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncLinkCoordinatorTest {

    private lateinit var db: TcgDatabase
    private lateinit var dao: CollectionDao
    private lateinit var cardDao: CardDao

    private class FakeLinkTransport(
        private val digestOf: (List<SyncItem>) -> String,
    ) : SyncLinkTransport {
        var preview: LinkPreview? = null
        var previewError: Exception? = null
        var commit: LinkCommitResult = LinkCommitResult.Linked(9L, emptyList())
        var commitError: Exception? = null
        val prepared = mutableListOf<List<SyncItem>>()
        val preparedIdentities = mutableListOf<String>()
        val committed = mutableListOf<Pair<LinkPreview, List<SyncOperation>>>()
        val committedIdentities = mutableListOf<String>()

        override suspend fun prepareLink(items: List<SyncItem>, expectedIdentity: String): LinkPreview {
            prepared += items
            preparedIdentities += expectedIdentity
            previewError?.let { throw it }
            return checkNotNull(preview)
        }

        override suspend fun commitLink(plan: LinkPreview, deviceId: String, operations: List<SyncOperation>, expectedIdentity: String): LinkCommitResult {
            committed += plan to operations
            committedIdentities += expectedIdentity
            commitError?.let { throw it }
            return commit
        }
    }

    private class FakeCards(private val cards: Map<String, Card>) : CardRepository {
        val requested = mutableListOf<String>()
        override suspend fun searchByName(name: String): List<Card> = error("unused")
        override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> = error("unused")
        override suspend fun getCard(id: String): Card? {
            requested += id
            return cards[id]
        }
        override suspend fun getSetCatalog(setId: String): app.tcg.core.data.repository.SetCatalogResult = error("unused")
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TcgDatabase::class.java).allowMainThreadQueries().build()
        dao = db.collectionDao()
        cardDao = db.cardDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(id: String, cardId: String = "base1-4", quantity: Int = 1, revision: Long = 1) = CollectionItemEntity(
        id = id, cardId = cardId, quantity = quantity, condition = Condition.NEAR_MINT,
        variant = CardVariant.NORMAL, pricePaidAmount = null, pricePaidCurrency = null,
        addedAt = 1_791_667_200_000L, revision = revision,
    )

    private fun card(id: String, name: String) = Card(
        id = id, name = name, setId = "base1", setName = "Base Set", collectorNumber = "4",
        rarity = null, hp = null, imageUrl = null, types = emptyList(), attacks = emptyList(),
        variants = setOf(CardVariant.NORMAL), prices = emptyList(), setTotal = 102,
    )

    private fun plan(items: List<SyncItem>, overrides: Map<String, Any> = emptyMap()) = LinkPreview(
        planId = "plan-1", digest = syncSnapshotDigest(items), expiresAt = "2026-10-12T20:00:00Z",
        remoteHighWater = 7L,
        localOnly = (overrides["localOnly"] as? List<String>) ?: items.map { it.id },
        cloudOnly = (overrides["cloudOnly"] as? List<String>) ?: emptyList(),
        identical = (overrides["identical"] as? List<String>) ?: emptyList(),
        conflicting = (overrides["conflicting"] as? List<LinkConflict>) ?: emptyList(),
        duplicateConfigurations = (overrides["duplicateConfigurations"] as? List<LinkDuplicate>) ?: emptyList(),
        remoteItems = (overrides["remoteItems"] as? List<RemoteLinkItem>) ?: emptyList(),
    )

    private fun coordinator(
        transport: FakeLinkTransport,
        cards: CardRepository,
    ): SyncLinkCoordinator = RoomSyncLinkCoordinator(
        transport = transport,
        store = RoomSyncLocalStore(db),
        dao = dao,
        cardDao = cardDao,
        cards = cards,
        deviceId = "device-0001",
    )

    private fun localSyncItems(vararg ids: String) = ids.map { id ->
        SyncItem(
            id = id, cardId = "base1-4", quantity = 1, variant = CardVariant.NORMAL,
            condition = Condition.NEAR_MINT, pricePaid = null, addedAt = 1_791_667_200_000L,
        )
    }

    @Test
    fun `preparePreview sends local items and returns the server plan`() = runTest {
        dao.upsertRemoteItem(entity("item-1"))
        val items = localSyncItems("item-1")
        val transport = FakeLinkTransport { items -> syncSnapshotDigest(items) }.apply { preview = plan(items) }

        val summary = coordinator(transport, FakeCards(emptyMap())).preparePreview("user-a")

        assertEquals(items.map { it.id }, transport.prepared.single().map { it.id })
        assertEquals("plan-1", summary.plan.planId)
        assertEquals(1, summary.localOnlyCount)
        assertEquals(0, summary.cloudOnlyCount)
    }

    @Test
    fun `confirming a clean preview links, imports remote rows, and binds the identity`() = runTest {
        dao.upsertRemoteItem(entity("item-1"))
        val items = localSyncItems("item-1")
        val remoteItem = SyncItem(
            id = "item-remote", cardId = "base2-3", quantity = 3, variant = CardVariant.NORMAL,
            condition = Condition.NEAR_MINT, pricePaid = null, addedAt = 1_791_667_200_000L,
        )
        val preview = plan(items, mapOf(
            "localOnly" to listOf("item-1"),
            "cloudOnly" to listOf("item-remote"),
            "remoteItems" to listOf(RemoteLinkItem(remoteItem, revision = 2L)),
        ))
        val transport = FakeLinkTransport { items -> syncSnapshotDigest(items) }.apply {
            this.preview = preview
            commit = LinkCommitResult.Linked(
                highWater = 9L,
                outcomes = listOf(PushOutcome.Applied(sequence = 1L)),
            )
        }
        val cards = FakeCards(mapOf("base2-3" to card("base2-3", "Blastoise")))

        val outcome = coordinator(transport, cards).confirm("user-a", preview)

        assertEquals(LinkOutcome.Linked(9L), outcome)
        val (_, operations) = transport.committed.single()
        assertEquals(1, operations.size)
        assertTrue(operations.single() is SyncOperation.Create)
        assertEquals("item-remote", dao.getById("item-remote")?.id)
        assertEquals("user-a", dao.getSyncBinding()?.identity)
        assertEquals(9L, RoomSyncLocalStore(db).lastSequence("user-a"))
        assertTrue("remote-only card must be cached for the import", cards.requested.contains("base2-3"))
    }

    @Test
    fun `a preview with conflicts is blocked and never commits`() = runTest {
        dao.upsertRemoteItem(entity("item-1"))
        val items = localSyncItems("item-1")
        val preview = plan(items, mapOf(
            "conflicting" to listOf(LinkConflict("item-1", "different-content")),
        ))
        val transport = FakeLinkTransport { items -> syncSnapshotDigest(items) }.apply { this.preview = preview }

        val outcome = coordinator(transport, FakeCards(emptyMap())).confirm("user-a", preview)

        assertEquals(LinkOutcome.Blocked, outcome)
        assertTrue("blocked preview must not commit", transport.committed.isEmpty())
        assertNull(dao.getSyncBinding())
    }

    @Test
    fun `a changed local collection is refused without any commit`() = runTest {
        dao.upsertRemoteItem(entity("item-1"))
        val items = localSyncItems("item-1")
        val preview = plan(items)
        val transport = FakeLinkTransport { items -> syncSnapshotDigest(items) }.apply { this.preview = preview }
        // Someone edited the collection after the preview was generated.
        dao.upsertRemoteItem(entity("item-1", quantity = 5))

        val outcome = coordinator(transport, FakeCards(emptyMap())).confirm("user-a", preview)

        assertEquals(LinkOutcome.LocalChanged, outcome)
        assertTrue("changed collection must not commit", transport.committed.isEmpty())
        assertNull(dao.getSyncBinding())
    }

    @Test
    fun `an unresolvable remote card fails the link and binds nothing`() = runTest {
        val items = emptyList<SyncItem>()
        val remoteItem = SyncItem(
            id = "item-remote", cardId = "base9-9", quantity = 1, variant = CardVariant.NORMAL,
            condition = Condition.NEAR_MINT, pricePaid = null, addedAt = 1L,
        )
        val preview = plan(items, mapOf(
            "localOnly" to emptyList<String>(),
            "cloudOnly" to listOf("item-remote"),
            "remoteItems" to listOf(RemoteLinkItem(remoteItem, revision = 1L)),
        ))
        val transport = FakeLinkTransport { items -> syncSnapshotDigest(items) }.apply { this.preview = preview }

        val outcome = coordinator(transport, FakeCards(emptyMap())).confirm("user-a", preview)

        assertTrue(outcome is LinkOutcome.Failed)
        assertTrue("unresolvable card must not commit", transport.committed.isEmpty())
        assertNull(dao.getSyncBinding())
    }

    @Test
    fun `a server refusal is surfaced as Refused and binds nothing`() = runTest {
        dao.upsertRemoteItem(entity("item-1"))
        val items = localSyncItems("item-1")
        val preview = plan(items)
        val transport = FakeLinkTransport { items -> syncSnapshotDigest(items) }.apply {
            this.preview = preview
            commit = LinkCommitResult.StalePreview
        }

        val outcome = coordinator(transport, FakeCards(emptyMap())).confirm("user-a", preview)

        assertEquals(LinkOutcome.Refused(LinkCommitResult.StalePreview), outcome)
        assertNull(dao.getSyncBinding())
    }

    @Test
    fun `link cancellation propagates instead of becoming a network failure`() = runTest {
        val items = localSyncItems()
        val preview = plan(items)
        val transport = FakeLinkTransport { syncSnapshotDigest(it) }.apply {
            this.preview = preview
            commitError = CancellationException("cancel link")
        }

        val failure = runCatching {
            coordinator(transport, FakeCards(emptyMap())).confirm("user-a", preview)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertNull(dao.getSyncBinding())
    }

    @Test
    fun `a rejected push outcome reports partial link and binds nothing`() = runTest {
        dao.upsertRemoteItem(entity("item-1"))
        val items = localSyncItems("item-1")
        val preview = plan(items)
        val transport = FakeLinkTransport { items -> syncSnapshotDigest(items) }.apply {
            this.preview = preview
            commit = LinkCommitResult.Linked(
                highWater = 9L,
                outcomes = listOf(PushOutcome.Conflict("id-collision")),
            )
        }

        val outcome = coordinator(transport, FakeCards(emptyMap())).confirm("user-a", preview)

        assertTrue(outcome is LinkOutcome.Partial)
        assertNull(dao.getSyncBinding())
    }

    @Test
    fun `structured partial server result retains rejected operation details and binds nothing`() = runTest {
        dao.upsertRemoteItem(entity("item-1"))
        val items = localSyncItems("item-1")
        val preview = plan(items)
        val transport = FakeLinkTransport { syncSnapshotDigest(items) }.apply {
            this.preview = preview
            commit = LinkCommitResult.Partial(
                highWater = 9L,
                outcomes = listOf(PushOutcome.Conflict("id-collision")),
            )
        }

        val outcome = coordinator(transport, FakeCards(emptyMap())).confirm("user-a", preview)

        assertEquals(LinkOutcome.Partial(listOf(PushOutcome.Conflict("id-collision"))), outcome)
        assertNull(dao.getSyncBinding())
    }
}
