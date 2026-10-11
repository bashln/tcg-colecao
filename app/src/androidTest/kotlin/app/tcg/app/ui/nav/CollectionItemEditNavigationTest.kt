package app.tcg.app.ui.nav

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.rememberNavController
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tcg.app.ui.theme.TcgTheme
import app.tcg.app.ui.card.CollectionItemEditTags
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.CollectionRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionItemEditNavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun owned_collection_item_opens_and_updates_that_exact_item_without_adding() {
        val item = CollectionItem(
            id = "owned-exact-1",
            cardId = "swsh3-136",
            quantity = 2,
            condition = Condition.NEAR_MINT,
            variant = CardVariant.NORMAL,
            pricePaid = Money(8.0, app.tcg.core.domain.model.Currency.USD),
            addedAt = 123L,
        )
        val repository = RecordingCollectionRepository(entry(item))
        lateinit var navController: androidx.navigation.NavHostController
        rule.setContent {
            TcgTheme {
                navController = rememberNavController()
                AppNav(
                    cardRepository = FakeCardRepository(),
                    collectionRepository = repository,
                    navController = navController,
                )
            }
        }
        rule.runOnIdle { navController.navigate("collection") }

        rule.onNodeWithText("Furret").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Preço pago (opcional)").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Editar carta").assertIsDisplayed()
        rule.onNodeWithContentDescription("Aumentar quantidade").performClick()
        rule.onNodeWithTag(CollectionItemEditTags.LIST).performScrollToNode(hasText("Salvar alterações"))
        rule.onNodeWithText("Salvar alterações").performClick()

        rule.waitUntil(timeoutMillis = 5_000) { repository.updatedItem != null }
        rule.runOnIdle {
            assertEquals("owned-exact-1", repository.updatedOriginal?.id)
            assertEquals(3, repository.updatedItem?.quantity)
            assertEquals(123L, repository.updatedItem?.addedAt)
            assertEquals(Money(8.0, app.tcg.core.domain.model.Currency.USD), repository.updatedItem?.pricePaid)
            assertEquals(0, repository.addCalls)
            assertEquals(Money(8.0, app.tcg.core.domain.model.Currency.USD), repository.updatedChanges?.pricePaid)
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Coleção").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class RecordingCollectionRepository(initial: CollectionEntry) : CollectionRepository {
        private val entries = MutableStateFlow(listOf(initial))
        var addCalls = 0
        var updatedOriginal: CollectionItem? = null
        var updatedItem: CollectionItem? = null
        var updatedChanges: CollectionItemChanges? = null

        override suspend fun add(
            card: Card,
            variant: CardVariant,
            condition: Condition,
            quantity: Int,
            pricePaid: Money?,
        ): CollectionItem {
            addCalls++
            return entries.value.single().item
        }

        override suspend fun list(): List<CollectionEntry> = entries.value

        override suspend fun getById(itemId: String): CollectionItemDetails? = entries.value
            .firstOrNull { it.item.id == itemId }
            ?.let { CollectionItemDetails(it.item, it.card) }

        override suspend fun update(original: CollectionItem, changes: CollectionItemChanges): CollectionUpdate {
            updatedOriginal = original
            val updated = original.copy(
                quantity = changes.quantity,
                variant = changes.variant,
                condition = changes.condition,
                pricePaid = changes.pricePaid,
            )
            updatedChanges = changes
            updatedItem = updated
            entries.value = entries.value.map { if (it.item.id == original.id) it.copy(item = updated) else it }
            return CollectionUpdate.Updated(updated)
        }

        override fun observe(): Flow<List<CollectionEntry>> = entries
        override suspend fun remove(itemId: String) = Unit
    }

    private class FakeCardRepository : CardRepository {
        override suspend fun searchByName(name: String): List<Card> = emptyList()
        override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> = emptyList()
        override suspend fun getCard(id: String): Card? = null
        override suspend fun getSetCatalog(setId: String): SetCatalogResult = SetCatalogResult.Unavailable
    }

    private fun entry(item: CollectionItem) = CollectionEntry(
        item = item,
        card = Card(
            id = item.cardId,
            name = "Furret",
            setId = "swsh3",
            setName = "Escuridão Incandescente",
            collectorNumber = "136",
            rarity = "Incomum",
            hp = null,
            types = emptyList(),
            attacks = emptyList(),
            imageUrl = null,
            variants = setOf(CardVariant.NORMAL, CardVariant.REVERSE_HOLOFOIL),
            prices = emptyList(),
        ),
    )
}
