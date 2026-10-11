package app.tcg.app.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shows_empty_state_that_invites_scanning() {
        val viewModel = ProfileViewModel(FakeCollectionRepository())
        rule.setContent { TcgTheme { ProfileScreen(viewModel = viewModel) } }

        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Ainda não há estatísticas").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Escanear uma carta").assertIsDisplayed()
    }

    @Test
    fun shows_breakdowns_and_investment_after_save() {
        val viewModel = ProfileViewModel(
            FakeCollectionRepository(
                listOf(entry(furret(), pricePaid = Money(0.10, Currency.USD))),
            ),
        )
        rule.setContent { TcgTheme { ProfileScreen(viewModel = viewModel) } }

        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Por condição").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Por condição").assertIsDisplayed()
        rule.onNodeWithText("Por variante").assertIsDisplayed()
        rule.onNodeWithText("Por raridade").assertIsDisplayed()
        rule.onNodeWithText("Investido").assertIsDisplayed()
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

    private fun entry(card: Card, pricePaid: Money?) = CollectionEntry(
        item = CollectionItem(
            id = "item-${card.id}",
            cardId = card.id,
            quantity = 1,
            condition = Condition.NEAR_MINT,
            variant = CardVariant.NORMAL,
            pricePaid = pricePaid,
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
