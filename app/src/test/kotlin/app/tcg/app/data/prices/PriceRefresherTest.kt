package app.tcg.app.data.prices

import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.data.repository.PriceRefreshReport
import app.tcg.core.data.repository.SetCatalogResult
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.CollectionItemChanges
import app.tcg.core.domain.model.CollectionItemDetails
import app.tcg.core.domain.model.CollectionUpdate
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PriceRefresherTest {

    @Test
    fun empty_collection_skips_the_card_repository() = runTest {
        val cards = FakeCards(PriceRefreshReport(requested = 0, refreshed = 0, unavailable = 0))

        val report = PriceRefresher(FakeCollection(emptyList()), cards).refresh()

        assertEquals(PriceRefreshReport(requested = 0, refreshed = 0, unavailable = 0), report)
        assertEquals(emptyList<List<String>>(), cards.refreshRequests)
    }

    @Test
    fun repeated_card_ids_are_refreshed_once() = runTest {
        val cards = FakeCards(PriceRefreshReport(requested = 1, refreshed = 1, unavailable = 0))
        val collection = FakeCollection(listOf(entry("swsh3-136"), entry("swsh3-136")))

        PriceRefresher(collection, cards).refresh()

        assertEquals(listOf(listOf("swsh3-136")), cards.refreshRequests)
    }

    @Test
    fun card_repository_report_is_returned_unchanged() = runTest {
        val expected = PriceRefreshReport(requested = 3, refreshed = 2, unavailable = 1)
        val cards = FakeCards(expected)
        val collection = FakeCollection(listOf(entry("a"), entry("b"), entry("c")))

        val report = PriceRefresher(collection, cards).refresh()

        assertEquals(expected, report)
    }

    private fun entry(cardId: String) = CollectionEntry(
        item = CollectionItem(
            id = "item-$cardId",
            cardId = cardId,
            quantity = 1,
            condition = Condition.NEAR_MINT,
            variant = CardVariant.NORMAL,
            pricePaid = null,
            addedAt = 0L,
        ),
        card = Card(
            id = cardId,
            name = "Furret",
            setId = "swsh3",
            setName = "Escuridão Incandescente",
            collectorNumber = "136",
            rarity = "Incomum",
            hp = 110,
            types = emptyList(),
            attacks = emptyList(),
            imageUrl = null,
            variants = setOf(CardVariant.NORMAL),
            prices = emptyList(),
        ),
    )

    private class FakeCollection(private val entries: List<CollectionEntry>) : CollectionRepository {
        override suspend fun add(
            card: Card,
            variant: CardVariant,
            condition: Condition,
            quantity: Int,
            pricePaid: Money?,
        ): CollectionItem = error("unused")

        override suspend fun list(): List<CollectionEntry> = entries

        override suspend fun getById(itemId: String): CollectionItemDetails? = error("unused")

        override suspend fun update(original: CollectionItem, changes: CollectionItemChanges): CollectionUpdate =
            error("unused")

        override fun observe(): Flow<List<CollectionEntry>> = flowOf(entries)

        override suspend fun remove(itemId: String) = Unit
    }

    private class FakeCards(private val report: PriceRefreshReport) : CardRepository {
        val refreshRequests = mutableListOf<List<String>>()

        override suspend fun searchByName(name: String): List<Card> = error("unused")

        override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> = error("unused")

        override suspend fun getCard(id: String): Card? = error("unused")

        override suspend fun getSetCatalog(setId: String): SetCatalogResult = error("unused")

        override suspend fun refreshPrices(cardIds: List<String>): PriceRefreshReport {
            refreshRequests += cardIds
            return report
        }
    }
}
