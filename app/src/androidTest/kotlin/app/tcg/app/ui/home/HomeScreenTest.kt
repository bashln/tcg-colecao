package app.tcg.app.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tcg.app.ui.theme.TcgTheme
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.CollectionItemChanges
import app.tcg.core.domain.model.CollectionItemDetails
import app.tcg.core.domain.model.CollectionUpdate
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shows_empty_state_that_invites_scanning() {
        val viewModel = HomeViewModel(FakeCollectionRepository())
        rule.setContent { TcgTheme { HomeScreen(viewModel = viewModel) } }

        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Sua coleção está vazia").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Escanear uma carta").assertIsDisplayed()
    }

    @Test
    fun shows_hero_and_most_valuable_after_save() {
        val viewModel = HomeViewModel(FakeCollectionRepository(listOf(entry(furret()))))
        rule.setContent { TcgTheme { HomeScreen(viewModel = viewModel) } }

        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Mais valiosas").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Mais valiosas").assertIsDisplayed()
        rule.onNodeWithText("Furret").assertIsDisplayed()
    }

    @Test
    fun most_valuable_card_opens_the_exact_collection_item() {
        val viewModel = HomeViewModel(FakeCollectionRepository(listOf(entry(furret()))))
        val selectedItems = mutableListOf<String>()
        rule.setContent {
            TcgTheme {
                HomeScreen(viewModel = viewModel, onCollectionItemSelected = selectedItems::add)
            }
        }

        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Furret").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Furret").performClick()

        rule.runOnIdle { assertEquals(listOf("item-swsh3-136"), selectedItems) }
    }

    private class FakeCollectionRepository(
        initial: List<CollectionEntry> = emptyList(),
    ) : CollectionRepository {
        val entries = MutableStateFlow(initial)

        override fun observe(): Flow<List<CollectionEntry>> = entries

        override suspend fun add(
            card: Card,
            variant: CardVariant,
            condition: Condition,
            quantity: Int,
            pricePaid: Money?,
        ): CollectionItem = error("not used")

        override suspend fun list(): List<CollectionEntry> = entries.value

        override suspend fun getById(itemId: String): CollectionItemDetails? = entries.value
            .firstOrNull { it.item.id == itemId }
            ?.let { CollectionItemDetails(it.item, it.card) }

        override suspend fun update(original: CollectionItem, changes: CollectionItemChanges): CollectionUpdate =
            CollectionUpdate.NotFound

        override suspend fun remove(itemId: String) = Unit
    }

    private fun entry(card: Card) = CollectionEntry(
        item = CollectionItem(
            id = "item-${card.id}",
            cardId = card.id,
            quantity = 1,
            condition = Condition.NEAR_MINT,
            variant = CardVariant.NORMAL,
            pricePaid = null,
            addedAt = 0L,
        ),
        card = card,
    )

    private fun furret() = Card(
        id = "swsh3-136",
        name = "Furret",
        setId = "swsh3",
        setName = "Escuridão Incandescente",
        collectorNumber = "136",
        rarity = "Incomum",
        hp = 110,
        types = listOf("Incolor"),
        attacks = emptyList(),
        imageUrl = null,
        variants = setOf(CardVariant.NORMAL),
        prices = listOf(
            CardPrice(
                source = PriceSource.TCGPLAYER,
                variant = CardVariant.NORMAL,
                currency = Currency.USD,
                market = 0.27,
                low = null, mid = null, high = null, trend = null,
                avg1 = null, avg7 = null, avg30 = null, updatedAt = null,
            ),
        ),
    )
}
