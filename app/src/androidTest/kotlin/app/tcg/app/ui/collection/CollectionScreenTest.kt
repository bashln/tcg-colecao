package app.tcg.app.ui.collection

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tcg.app.ui.theme.TcgTheme
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shows_empty_state_then_item_after_save() {
        val repository = FakeCollectionRepository()
        val viewModel = CollectionViewModel(repository)
        rule.setContent {
            TcgTheme {
                CollectionScreen(viewModel = viewModel)
            }
        }

        rule.onNodeWithText("Sua coleção está vazia").assertIsDisplayed()

        rule.runOnUiThread {
            repository.entries.value = listOf(entry(furret()))
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Furret").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Furret").assertIsDisplayed()
    }

    @Test
    fun query_without_matches_shows_actionable_empty_state() {
        val repository = FakeCollectionRepository(listOf(entry(furret())))
        val viewModel = CollectionViewModel(repository)
        rule.setContent {
            TcgTheme {
                CollectionScreen(viewModel = viewModel)
            }
        }

        viewModel.onQuery("nada")
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Nenhuma carta encontrada").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Limpar busca").assertIsDisplayed()
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
        prices = emptyList(),
    )
}
