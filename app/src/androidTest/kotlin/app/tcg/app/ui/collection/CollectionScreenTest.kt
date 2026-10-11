package app.tcg.app.ui.collection

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tcg.app.ui.theme.TcgTheme
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
import app.tcg.core.domain.model.SetCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun collection_sections_have_a_clear_selected_tab() {
        val viewModel = CollectionViewModel(FakeCollectionRepository(listOf(entry(furret()))), FakeCardRepository())
        rule.setContent {
            TcgTheme {
                CollectionScreen(viewModel = viewModel)
            }
        }

        rule.onNodeWithText("Cartas").assertIsSelected()
        rule.onNodeWithText("Expansões").assertIsNotSelected()
        rule.onNodeWithText("Expansões").performClick()
        rule.onNodeWithText("Expansões").assertIsSelected()
        rule.onNodeWithText("Cartas").assertIsNotSelected()
    }

    @Test
    fun shows_empty_state_then_item_after_save() {
        val repository = FakeCollectionRepository()
        val viewModel = CollectionViewModel(repository, FakeCardRepository())
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
        val viewModel = CollectionViewModel(repository, FakeCardRepository())
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

    @Test
    fun expansion_list_shows_distinct_owned_card_count() {
        val repository = FakeCollectionRepository(listOf(entry(furret())))
        val viewModel = CollectionViewModel(repository, FakeCardRepository())
        rule.setContent {
            TcgTheme {
                CollectionScreen(viewModel = viewModel)
            }
        }

        rule.onNodeWithText("Expansões").performClick()

        rule.onNodeWithText("Escuridão Incandescente").assertIsDisplayed()
        rule.onNodeWithText("1 carta distinta na coleção").assertIsDisplayed()
    }

    @Test
    fun missing_filter_shows_unowned_cards_from_complete_catalog() {
        val repository = FakeCollectionRepository(listOf(entry(furret())))
        val missingCard = furret().copy(id = "swsh3-137", name = "Crobat", collectorNumber = "137")
        val cardRepository = FakeCardRepository(
            SetCatalogResult.Complete(SetCatalog("swsh3", listOf(furret(), missingCard))),
        )
        val viewModel = CollectionViewModel(repository, cardRepository)
        rule.setContent {
            TcgTheme {
                CollectionScreen(viewModel = viewModel)
            }
        }

        rule.onNodeWithText("Expansões").performClick()
        rule.onNodeWithText("Escuridão Incandescente").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Crobat").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("1 de 2 cartas desta expansão").assertIsDisplayed()
        rule.onNodeWithText("Faltam").performClick()

        rule.onNodeWithText("Crobat").assertIsDisplayed()
        rule.onNodeWithText("Toque para ver e adicionar").assertIsDisplayed()
    }

    @Test
    fun global_missing_lens_shows_missing_cards_without_opening_an_expansion() {
        val repository = FakeCollectionRepository(listOf(entry(furret())))
        val missingCard = furret().copy(id = "swsh3-137", name = "Crobat", collectorNumber = "137")
        val viewModel = CollectionViewModel(
            repository,
            FakeCardRepository(SetCatalogResult.Complete(SetCatalog("swsh3", listOf(furret(), missingCard)))),
        )
        val selectedCardIds = mutableListOf<String>()
        rule.setContent {
            TcgTheme {
                CollectionScreen(viewModel = viewModel, onCardSelected = selectedCardIds::add)
            }
        }

        rule.onNodeWithText("Faltam").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("1 carta faltante nas expansões verificadas").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Crobat").assertIsDisplayed().performClick()

        rule.runOnIdle { assertEquals(listOf("swsh3-137"), selectedCardIds) }
    }

    @Test
    fun global_missing_loading_state_names_expansions_being_checked() {
        val secondSetCard = furret().copy(id = "sv1-042", setId = "sv1", setName = "Fábulas Nebulosas")
        val pending = CompletableDeferred<SetCatalogResult>()
        val viewModel = CollectionViewModel(
            FakeCollectionRepository(listOf(entry(furret()), entry(secondSetCard))),
            FakeCardRepository(catalogResult = SetCatalogResult.Unavailable, pendingCatalog = pending),
        )
        rule.setContent { TcgTheme { CollectionScreen(viewModel = viewModel) } }

        rule.onNodeWithText("Faltam").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Aguardando catálogo: Escuridão Incandescente").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithText("Aguardando catálogo: Fábulas Nebulosas").fetchSemanticsNodes().isNotEmpty()
        }

        pending.complete(SetCatalogResult.Unavailable)
    }

    @Test
    fun empty_global_missing_state_keeps_scan_action_available() {
        var scanClicks = 0
        val viewModel = CollectionViewModel(FakeCollectionRepository(), FakeCardRepository())
        rule.setContent {
            TcgTheme {
                CollectionScreen(viewModel = viewModel, onScanClick = { scanClicks++ })
            }
        }

        rule.onNodeWithText("Faltam").performClick()
        rule.onNodeWithText("Escanear uma carta").performClick()
        rule.runOnIdle { assertEquals(1, scanClicks) }
    }

    @Test
    fun unavailable_global_missing_state_keeps_owned_counts_visible() {
        val viewModel = CollectionViewModel(
            FakeCollectionRepository(listOf(entry(furret(), quantity = 3))),
            FakeCardRepository(),
        )
        rule.setContent { TcgTheme { CollectionScreen(viewModel = viewModel) } }

        rule.onNodeWithText("Faltam").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Cartas diferentes: 1 · Cópias salvas: 3. Confira sua conexão e tente novamente. Suas cartas continuam disponíveis em Tenho.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Tentar novamente").assertIsDisplayed()
    }

    @Test
    fun owned_and_missing_set_cards_open_their_details() {
        val repository = FakeCollectionRepository(listOf(entry(furret())))
        val missingCard = furret().copy(id = "swsh3-137", name = "Crobat", collectorNumber = "137")
        val cardRepository = FakeCardRepository(
            SetCatalogResult.Complete(SetCatalog("swsh3", listOf(furret(), missingCard))),
        )
        val viewModel = CollectionViewModel(repository, cardRepository)
        val selectedCardIds = mutableListOf<String>()
        val selectedItemIds = mutableListOf<String>()
        rule.setContent {
            TcgTheme {
                CollectionScreen(
                    viewModel = viewModel,
                    onCardSelected = { selectedCardIds.add(it) },
                    onCollectionItemSelected = { selectedItemIds.add(it) },
                )
            }
        }

        rule.onNodeWithText("Expansões").performClick()
        rule.onNodeWithText("Escuridão Incandescente").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("1 de 2 cartas desta expansão").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Tenho").performClick()
        rule.onNodeWithText("Furret").performClick()
        rule.runOnIdle {
            assertEquals(listOf("item-swsh3-136"), selectedItemIds)
            assertEquals(emptyList<String>(), selectedCardIds)
        }
        rule.onNodeWithText("Faltam").performClick()
        rule.onNodeWithText("Crobat").performClick()
        rule.runOnIdle {
            assertEquals(listOf("swsh3-137"), selectedCardIds)
        }
    }

    @Test
    fun collection_card_opens_its_details() {
        val repository = FakeCollectionRepository(listOf(entry(furret())))
        val viewModel = CollectionViewModel(repository, FakeCardRepository())
        val selectedItemIds = mutableListOf<String>()
        rule.setContent {
            TcgTheme {
                CollectionScreen(
                    viewModel = viewModel,
                    onCollectionItemSelected = { selectedItemIds.add(it) },
                )
            }
        }

        rule.onNodeWithText("Furret").performClick()

        rule.runOnIdle {
            assertEquals(listOf("item-swsh3-136"), selectedItemIds)
        }
    }

    @Test
    fun owned_card_opens_from_unavailable_set_catalog() {
        val repository = FakeCollectionRepository(listOf(entry(furret())))
        val viewModel = CollectionViewModel(repository, FakeCardRepository())
        val selectedItemIds = mutableListOf<String>()
        rule.setContent {
            TcgTheme {
                CollectionScreen(
                    viewModel = viewModel,
                    onCollectionItemSelected = { selectedItemIds.add(it) },
                )
            }
        }

        rule.onNodeWithText("Expansões").performClick()
        rule.onNodeWithText("Escuridão Incandescente").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Não foi possível carregar o catálogo").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Furret").performClick()

        rule.runOnIdle {
            assertEquals(listOf("item-swsh3-136"), selectedItemIds)
        }
    }

    @Test
    fun owned_catalog_card_prompts_for_the_exact_saved_copy() {
        val first = entry(furret())
        val second = entry(furret(), itemId = "item-swsh3-136-reverse", variant = CardVariant.REVERSE_HOLOFOIL, quantity = 2)
        val viewModel = CollectionViewModel(
            FakeCollectionRepository(listOf(first, second)),
            FakeCardRepository(SetCatalogResult.Complete(SetCatalog("swsh3", listOf(furret())))),
        )
        val selectedItemIds = mutableListOf<String>()
        rule.setContent {
            TcgTheme {
                CollectionScreen(viewModel = viewModel, onCollectionItemSelected = selectedItemIds::add)
            }
        }

        rule.onNodeWithText("Expansões").performClick()
        rule.onNodeWithText("Escuridão Incandescente").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("1 de 1 cartas desta expansão").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Furret").performClick()
        rule.onNodeWithText("Reverse · NM · ×2").performClick()

        rule.runOnIdle { assertEquals(listOf("item-swsh3-136-reverse"), selectedItemIds) }
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

    private class FakeCardRepository(
        private val catalogResult: SetCatalogResult = SetCatalogResult.Unavailable,
        private val pendingCatalog: CompletableDeferred<SetCatalogResult>? = null,
    ) : CardRepository {
        override suspend fun searchByName(name: String): List<Card> = emptyList()
        override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> = emptyList()
        override suspend fun getCard(id: String): Card? = null
        override suspend fun getSetCatalog(setId: String): SetCatalogResult = pendingCatalog?.await() ?: catalogResult
    }

    private fun entry(
        card: Card,
        itemId: String = "item-${card.id}",
        variant: CardVariant = CardVariant.NORMAL,
        quantity: Int = 1,
    ) = CollectionEntry(
        item = CollectionItem(
            id = itemId,
            cardId = card.id,
            quantity = quantity,
            condition = Condition.NEAR_MINT,
            variant = variant,
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
