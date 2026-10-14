package app.tcg.app.ui.card

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardDetailScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun showDetail() {
        val viewModel = CardDetailViewModel(card(), FakeCollectionRepository())
        rule.setContent {
            TcgTheme {
                CardDetailScreen(viewModel = viewModel)
            }
        }
    }

    private fun scrollToSave() {
        rule.onNodeWithTag(CardDetailTags.LIST)
            .performScrollToNode(hasText("Salvar na coleção"))
    }

    @Test
    fun shows_both_price_labels_and_save_button() {
        showDetail()

        rule.onNodeWithText("TCGplayer").assertIsDisplayed()
        rule.onNodeWithText("Cardmarket").assertIsDisplayed()

        scrollToSave()
        rule.onNodeWithText("Salvar na coleção").assertIsDisplayed()
    }

    @Test
    fun saving_shows_confirmation_snackbar() {
        showDetail()

        scrollToSave()
        rule.onNodeWithText("Salvar na coleção").performClick()

        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Carta salva na coleção").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Carta salva na coleção").assertIsDisplayed()
    }

    private class FakeCollectionRepository : CollectionRepository {
        override suspend fun add(
            card: Card,
            variant: CardVariant,
            condition: Condition,
            quantity: Int,
            pricePaid: Money?,
        ): CollectionItem = CollectionItem(
            id = "item-1",
            cardId = card.id,
            quantity = quantity,
            condition = condition,
            variant = variant,
            pricePaid = pricePaid,
            addedAt = 0L,
        )

        override suspend fun list(): List<CollectionEntry> = emptyList()

        override suspend fun getById(itemId: String): CollectionItemDetails? = null

        override suspend fun update(original: CollectionItem, changes: CollectionItemChanges): CollectionUpdate =
            CollectionUpdate.NotFound

        override fun observe(): Flow<List<CollectionEntry>> = flowOf(emptyList())

        override suspend fun remove(itemId: String) = Unit
    }

    private fun card() = Card(
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
