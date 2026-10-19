package app.tcg.app.data.backup

import app.tcg.app.data.local.CollectionItemEntity
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPlannerTest {
    private fun createEntry(
        id: String,
        cardId: String,
        quantity: Int,
        variant: CardVariant = CardVariant.NORMAL,
        condition: Condition = Condition.NEAR_MINT,
    ): WebEntry = WebEntry(
        item = WebItem(
            id = id,
            cardId = cardId,
            quantity = quantity,
            variant = variant,
            condition = condition,
            pricePaid = null,
            addedAt = 1000L,
            revision = 1L,
        ),
        card = WebCard(
            id = cardId,
            name = "Card $cardId",
            setId = "set1",
            setName = "Set 1",
            collectorNumber = "1",
            imageUrl = null,
            variants = listOf(variant),
            prices = emptyList(),
            setTotal = 100,
        ),
    )

    @Test
    fun plans_all_inserts_for_empty_local_collection() {
        val backup = ValidatedWebBackup(
            exportedAt = "2026-10-18T10:00:00Z",
            entries = listOf(createEntry("item-1", "card-1", 2)),
        )
        val localState = ImportLocalState(
            items = emptyList(),
            markers = emptyMap(),
            binding = null,
            cardIds = emptySet(),
        )

        val ticket = ImportPlanner.createTicket(backup, localState)
        assertEquals(1, ticket.summary.added)
        assertEquals(0, ticket.summary.merged)
        assertEquals(0, ticket.summary.unchanged)
        assertEquals(1, ticket.actions.size)
        assertTrue(ticket.actions.first() is ImportAction.Insert)
    }

    @Test
    fun plans_unchanged_when_source_item_matches_marker_signature() {
        val entry = createEntry("item-1", "card-1", 2)
        val backup = ValidatedWebBackup(
            exportedAt = "2026-10-18T10:00:00Z",
            entries = listOf(entry),
        )
        val sig = WebBackupAdapter.itemSignature(entry.item)
        val localState = ImportLocalState(
            items = emptyList(),
            markers = mapOf("item-1" to sig),
            binding = null,
            cardIds = emptySet(),
        )

        val ticket = ImportPlanner.createTicket(backup, localState)
        assertEquals(0, ticket.summary.added)
        assertEquals(0, ticket.summary.merged)
        assertEquals(1, ticket.summary.unchanged)
        assertEquals(ImportAction.Unchanged("item-1"), ticket.actions.first())
    }

    @Test
    fun rejects_when_source_item_has_altered_signature_against_marker() {
        val entry = createEntry("item-1", "card-1", 2)
        val backup = ValidatedWebBackup(
            exportedAt = "2026-10-18T10:00:00Z",
            entries = listOf(entry),
        )
        val localState = ImportLocalState(
            items = emptyList(),
            markers = mapOf("item-1" to "stale-signature"),
            binding = null,
            cardIds = emptySet(),
        )

        val ex = assertThrows(InvalidBackupException::class.java) {
            ImportPlanner.createTicket(backup, localState)
        }
        assertTrue(ex.message!!.contains("item-1"))
    }

    @Test
    fun plans_unchanged_when_updated_local_item_matches_backup_despite_stale_marker() {
        val priorEntry = createEntry("item-1", "card-1", 2)
        val updatedEntry = createEntry("item-1", "card-1", 4)
        val backup = ValidatedWebBackup(
            exportedAt = "2026-10-18T10:00:00Z",
            entries = listOf(updatedEntry),
        )
        val localItem = CollectionItemEntity(
            id = "item-1",
            cardId = "card-1",
            quantity = 4,
            condition = Condition.NEAR_MINT,
            variant = CardVariant.NORMAL,
            pricePaidAmount = null,
            pricePaidCurrency = null,
            addedAt = 1000L,
            revision = 2L,
        )
        val localState = ImportLocalState(
            items = listOf(localItem),
            markers = mapOf("item-1" to WebBackupAdapter.itemSignature(priorEntry.item)),
            binding = null,
            cardIds = setOf("card-1"),
        )

        val ticket = ImportPlanner.createTicket(backup, localState)
        assertEquals(ImportSummary(added = 0, merged = 0, unchanged = 1), ticket.summary)
        assertEquals(ImportAction.Unchanged("item-1"), ticket.actions.single())
    }

    @Test
    fun plans_merge_when_configuration_matches_local_item_with_different_id() {
        val entry = createEntry("item-source-2", "card-1", 3)
        val backup = ValidatedWebBackup(
            exportedAt = "2026-10-18T10:00:00Z",
            entries = listOf(entry),
        )
        val localItem = CollectionItemEntity(
            id = "item-local-1",
            cardId = "card-1",
            quantity = 2,
            condition = Condition.NEAR_MINT,
            variant = CardVariant.NORMAL,
            pricePaidAmount = null,
            pricePaidCurrency = null,
            addedAt = 500L,
            revision = 1L,
        )
        val localState = ImportLocalState(
            items = listOf(localItem),
            markers = emptyMap(),
            binding = null,
            cardIds = setOf("card-1"),
        )

        val ticket = ImportPlanner.createTicket(backup, localState)
        assertEquals(0, ticket.summary.added)
        assertEquals(1, ticket.summary.merged)
        assertEquals(0, ticket.summary.unchanged)
        val mergeAction = ticket.actions.first() as ImportAction.Merge
        assertEquals("item-local-1", mergeAction.localItemId)
        assertEquals(entry, mergeAction.entry)
    }

    @Test
    fun rejects_merge_when_total_quantity_exceeds_limit() {
        val entry = createEntry("item-source-2", "card-1", 60_000)
        val backup = ValidatedWebBackup(
            exportedAt = "2026-10-18T10:00:00Z",
            entries = listOf(entry),
        )
        val localItem = CollectionItemEntity(
            id = "item-local-1",
            cardId = "card-1",
            quantity = 50_000,
            condition = Condition.NEAR_MINT,
            variant = CardVariant.NORMAL,
            pricePaidAmount = null,
            pricePaidCurrency = null,
            addedAt = 500L,
            revision = 1L,
        )
        val localState = ImportLocalState(
            items = listOf(localItem),
            markers = emptyMap(),
            binding = null,
            cardIds = setOf("card-1"),
        )

        val ex = assertThrows(InvalidBackupException::class.java) {
            ImportPlanner.createTicket(backup, localState)
        }
        assertEquals("Quantidade combinada excede o limite", ex.message)
    }
}
