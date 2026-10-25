package app.tcg.app.ui.collection

import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.PriceRefreshReport
import app.tcg.core.data.repository.SetCatalogResult
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
import app.tcg.core.domain.model.SetCatalog
import app.tcg.app.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test fun total_is_zero_for_empty_collection() = runTest {
        val vm = CollectionViewModel(FakeCollectionRepository(emptyList()), FakeCardRepository(SetCatalogResult.Unavailable))
        advanceUntilIdle()
        assertEquals(Money(0.0, Currency.USD), vm.state.value.total)
    }

    @Test fun collection_remains_loading_until_repository_emits_first_snapshot() = runTest {
        val vm = CollectionViewModel(
            FakeCollectionRepository(listOf(entryNamed("Furret")), initialEmissionDelayMs = 1_000),
            FakeCardRepository(SetCatalogResult.Unavailable),
        )
        runCurrent()

        assertTrue(vm.state.value.loading)

        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(vm.state.value.loading)
        assertEquals(listOf("Furret"), vm.state.value.entries.map { it.card.name })
    }

    @Test fun total_sums_priced_entries_and_list_renders_card() = runTest {
        val vm = CollectionViewModel(FakeCollectionRepository(listOf(entryPricedAt(2.0, qty = 3))), FakeCardRepository(SetCatalogResult.Unavailable))
        advanceUntilIdle()
        assertEquals(Money(6.0, Currency.USD), vm.state.value.total)
        assertEquals("Furret", vm.state.value.entries.first().card.name)
    }

    @Test fun query_filters_by_name() = runTest {
        val vm = CollectionViewModel(FakeCollectionRepository(listOf(entryNamed("Furret"), entryNamed("Charizard"))), FakeCardRepository(SetCatalogResult.Unavailable))
        vm.onQuery("fur")
        advanceUntilIdle()
        assertEquals(listOf("Furret"), vm.state.value.entries.map { it.card.name })
    }

    @Test fun variant_filter_keeps_only_owned_cards_with_that_variant() = runTest {
        val vm = CollectionViewModel(
            FakeCollectionRepository(
                listOf(
                    entryNamed("Furret"),
                    entryNamed("Furret Reverse", variant = CardVariant.REVERSE_HOLOFOIL),
                    entryNamed("Charizard", variant = CardVariant.HOLOFOIL),
                ),
            ),
            FakeCardRepository(SetCatalogResult.Unavailable),
        )
        advanceUntilIdle()

        vm.onVariantFilter(CardVariant.HOLOFOIL)
        advanceUntilIdle()

        assertEquals(listOf("Charizard"), vm.state.value.entries.map { it.card.name })
        assertEquals(CardVariant.HOLOFOIL, vm.state.value.variantFilter)
        assertEquals(null, vm.state.value.conditionFilter)
    }

    @Test fun condition_filter_keeps_only_owned_cards_with_that_condition() = runTest {
        val vm = CollectionViewModel(
            FakeCollectionRepository(
                listOf(
                    entryNamed("Furret"),
                    entryNamed("Furret Played", condition = Condition.LIGHTLY_PLAYED),
                    entryNamed("Charizard", condition = Condition.LIGHTLY_PLAYED),
                ),
            ),
            FakeCardRepository(SetCatalogResult.Unavailable),
        )
        advanceUntilIdle()

        vm.onConditionFilter(Condition.LIGHTLY_PLAYED)
        advanceUntilIdle()

        assertEquals(listOf("Furret Played", "Charizard"), vm.state.value.entries.map { it.card.name })
        assertEquals(Condition.LIGHTLY_PLAYED, vm.state.value.conditionFilter)
        assertEquals(null, vm.state.value.variantFilter)
    }

    @Test fun variant_condition_and_search_narrow_the_owned_cards_together() = runTest {
        val vm = CollectionViewModel(
            FakeCollectionRepository(
                listOf(
                    entryNamed("Furret", variant = CardVariant.HOLOFOIL),
                    entryNamed("Furret Played", variant = CardVariant.HOLOFOIL, condition = Condition.LIGHTLY_PLAYED),
                    entryNamed("Furret Reverse", variant = CardVariant.REVERSE_HOLOFOIL),
                    entryNamed("Charizard", variant = CardVariant.HOLOFOIL),
                ),
            ),
            FakeCardRepository(SetCatalogResult.Unavailable),
        )
        advanceUntilIdle()

        vm.onVariantFilter(CardVariant.HOLOFOIL)
        vm.onConditionFilter(Condition.NEAR_MINT)
        vm.onQuery("furret")
        advanceUntilIdle()

        assertEquals(listOf("Furret"), vm.state.value.entries.map { it.card.name })
    }

    @Test fun clearing_both_filters_restores_every_owned_card() = runTest {
        val vm = CollectionViewModel(
            FakeCollectionRepository(
                listOf(
                    entryNamed("Furret"),
                    entryNamed("Furret Played", condition = Condition.LIGHTLY_PLAYED),
                    entryNamed("Charizard", variant = CardVariant.HOLOFOIL),
                ),
            ),
            FakeCardRepository(SetCatalogResult.Unavailable),
        )
        advanceUntilIdle()

        vm.onVariantFilter(CardVariant.HOLOFOIL)
        vm.onConditionFilter(Condition.LIGHTLY_PLAYED)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), vm.state.value.entries.map { it.card.name })

        vm.onClearCardFilters()
        advanceUntilIdle()

        assertEquals(
            listOf("Furret", "Furret Played", "Charizard"),
            vm.state.value.entries.map { it.card.name },
        )
        assertEquals(null, vm.state.value.variantFilter)
        assertEquals(null, vm.state.value.conditionFilter)
    }

    @Test fun switching_section_clears_both_card_filters() = runTest {
        val vm = CollectionViewModel(
            FakeCollectionRepository(listOf(entryNamed("Furret"))),
            FakeCardRepository(SetCatalogResult.Unavailable),
        )
        advanceUntilIdle()
        vm.onVariantFilter(CardVariant.NORMAL)
        vm.onConditionFilter(Condition.NEAR_MINT)
        vm.onQuery("fur")

        vm.onSection(CollectionSection.SETS)
        advanceUntilIdle()

        assertEquals(null, vm.state.value.variantFilter)
        assertEquals(null, vm.state.value.conditionFilter)
        assertEquals("", vm.state.value.query)
    }

    @Test fun switching_lens_clears_both_card_filters() = runTest {
        val vm = CollectionViewModel(
            FakeCollectionRepository(listOf(entryNamed("Furret"))),
            FakeCardRepository(SetCatalogResult.Unavailable),
        )
        advanceUntilIdle()
        vm.onVariantFilter(CardVariant.NORMAL)
        vm.onConditionFilter(Condition.NEAR_MINT)
        vm.onQuery("fur")

        vm.onCardsLens(CardsLens.MISSING)
        advanceUntilIdle()

        assertEquals(null, vm.state.value.variantFilter)
        assertEquals(null, vm.state.value.conditionFilter)
        assertEquals("", vm.state.value.query)
    }

    @Test fun totals_stay_unfiltered_while_the_visible_list_is_filtered() = runTest {
        val normal = entryPricedAt(2.0, qty = 3)
        val holo = normal.copy(
            item = normal.item.copy(id = "item-furret-holo", variant = CardVariant.HOLOFOIL, quantity = 1),
            card = normal.card.copy(
                id = "card-furret-holo",
                prices = listOf(price(PriceSource.TCGPLAYER, CardVariant.HOLOFOIL, 4.0)),
            ),
        )
        val vm = CollectionViewModel(
            FakeCollectionRepository(listOf(normal, holo)),
            FakeCardRepository(SetCatalogResult.Unavailable),
        )
        advanceUntilIdle()

        vm.onVariantFilter(CardVariant.HOLOFOIL)
        advanceUntilIdle()

        assertEquals(listOf("item-furret-holo"), vm.state.value.entries.map { it.item.id })
        assertEquals(Money(10.0, Currency.USD), vm.state.value.total)
        assertEquals(2, vm.state.value.distinctOwnedCards)
        assertEquals(4, vm.state.value.savedCopies)
        assertEquals(listOf(CardVariant.NORMAL, CardVariant.HOLOFOIL), vm.state.value.ownedVariants)
    }

    @Test fun changing_collection_section_clears_the_previous_search() = runTest {
        val vm = CollectionViewModel(
            FakeCollectionRepository(listOf(entryNamed("Furret"))),
            FakeCardRepository(SetCatalogResult.Unavailable),
        )
        vm.onQuery("Furret")
        advanceUntilIdle()

        vm.onSection(CollectionSection.SETS)
        advanceUntilIdle()

        assertEquals("", vm.state.value.query)
        assertEquals(listOf("Escuridão Incandescente"), vm.state.value.ownedSets.map { it.name })
    }

    @Test fun emitting_new_list_after_construction_updates_entries_and_total() = runTest {
        val repository = FakeCollectionRepository(emptyList())
        val vm = CollectionViewModel(repository, FakeCardRepository(SetCatalogResult.Unavailable))
        advanceUntilIdle()
        assertEquals(Money(0.0, Currency.USD), vm.state.value.total)

        repository.entries.value = listOf(entryPricedAt(2.0, qty = 3))
        advanceUntilIdle()

        assertEquals("Furret", vm.state.value.entries.first().card.name)
        assertEquals(Money(6.0, Currency.USD), vm.state.value.total)
    }

    @Test fun set_progress_counts_unique_cards_and_separates_missing_cards() = runTest {
        val furret = entryNamed("Furret")
        val crobat = furret.copy(
            item = furret.item.copy(id = "item-Crobat", cardId = "card-Crobat"),
            card = furret.card.copy(id = "card-Crobat", name = "Crobat", collectorNumber = "001"),
        )
        val cardRepository = FakeCardRepository(
            SetCatalogResult.Complete(SetCatalog("swsh3", listOf(furret.card, crobat.card))),
        )
        val vm = CollectionViewModel(FakeCollectionRepository(listOf(furret)), cardRepository)

        vm.onSetSelected("swsh3")
        advanceUntilIdle()

        assertEquals(listOf("Furret" to true, "Crobat" to false), vm.state.value.setCards.map { it.card.name to it.isOwned })
        assertEquals(1, vm.state.value.ownedSets.single().ownedCards)

        vm.onOwnershipFilter(OwnershipFilter.MISSING)
        advanceUntilIdle()

        assertEquals(listOf("Crobat"), vm.state.value.setCards.map { it.card.name })
    }

    @Test fun set_catalog_failure_keeps_owned_cards_and_does_not_claim_missing_cards() = runTest {
        val owned = entryNamed("Furret")
        val vm = CollectionViewModel(
            FakeCollectionRepository(listOf(owned)),
            FakeCardRepository(SetCatalogResult.Unavailable),
        )

        vm.onSetSelected("swsh3")
        advanceUntilIdle()

        assertEquals(SelectedSetState.Unavailable("swsh3"), vm.state.value.selectedSet)
        assertEquals(listOf("Furret"), vm.state.value.ownedSetEntries.map { it.card.name })
        assertEquals(emptyList<String>(), vm.state.value.setCards.map { it.card.name })
    }

    @Test fun global_missing_lens_loads_started_sets_and_reuses_catalog_for_checklist() = runTest {
        val owned = entryNamed("Furret")
        val missing = owned.card.copy(id = "card-Crobat", name = "Crobat", collectorNumber = "137")
        val cardRepository = FakeCardRepository(
            SetCatalogResult.Complete(SetCatalog("swsh3", listOf(owned.card, missing))),
        )
        val vm = CollectionViewModel(FakeCollectionRepository(listOf(owned)), cardRepository)
        advanceUntilIdle()

        assertEquals(0, cardRepository.catalogRequests)
        vm.onCardsLens(CardsLens.MISSING)
        advanceUntilIdle()

        assertEquals(1, cardRepository.catalogRequests)
        val overview = vm.state.value.globalMissing as GlobalMissingState.Complete
        assertEquals(listOf("Crobat"), overview.verified.single().cards.map { it.name })

        vm.onSetSelected("swsh3")
        advanceUntilIdle()

        assertEquals(1, cardRepository.catalogRequests)
        assertEquals(SelectedSetState.Ready(SetCatalog("swsh3", listOf(owned.card, missing))), vm.state.value.selectedSet)
    }

    @Test fun global_missing_keeps_unavailable_sets_visible_and_does_not_report_zero_missing() = runTest {
        val owned = entryNamed("Furret")
        val vm = CollectionViewModel(
            FakeCollectionRepository(listOf(owned)),
            FakeCardRepository(SetCatalogResult.Unavailable),
        )

        vm.onCardsLens(CardsLens.MISSING)
        advanceUntilIdle()

        assertEquals(
            GlobalMissingState.Unavailable(
                listOf(StartedSet("swsh3", "Escuridão Incandescente", setOf("card-Furret"), 1)),
            ),
            vm.state.value.globalMissing,
        )
    }

    @Test fun retrying_global_missing_reloads_only_the_failed_catalog() = runTest {
        val owned = entryNamed("Furret")
        val cardRepository = FakeCardRepository(SetCatalogResult.Unavailable)
        val vm = CollectionViewModel(FakeCollectionRepository(listOf(owned)), cardRepository)
        vm.onCardsLens(CardsLens.MISSING)
        advanceUntilIdle()
        assertEquals(1, cardRepository.catalogRequests)

        cardRepository.result = SetCatalogResult.Complete(SetCatalog("swsh3", listOf(owned.card)))
        vm.onRetryMissingCatalogs()
        advanceUntilIdle()

        assertEquals(2, cardRepository.catalogRequests)
        assertEquals(GlobalMissingState.Complete(listOf(MissingGroup(
            StartedSet("swsh3", "Escuridão Incandescente", setOf("card-Furret"), 1),
            cards = emptyList(),
            savedIdsOutsideCatalog = emptySet(),
        ))), vm.state.value.globalMissing)
    }

    @Test fun opening_unavailable_expansion_retries_and_updates_checklist_when_catalog_arrives() = runTest {
        val owned = entryNamed("Furret")
        val cardRepository = FakeCardRepository(SetCatalogResult.Unavailable)
        val vm = CollectionViewModel(FakeCollectionRepository(listOf(owned)), cardRepository)
        vm.onCardsLens(CardsLens.MISSING)
        advanceUntilIdle()
        cardRepository.result = SetCatalogResult.Complete(SetCatalog("swsh3", listOf(owned.card)))

        vm.onSetSelected("swsh3")
        advanceUntilIdle()

        assertEquals(2, cardRepository.catalogRequests)
        assertEquals(SelectedSetState.Ready(SetCatalog("swsh3", listOf(owned.card))), vm.state.value.selectedSet)
    }

    @Test fun missing_overview_summary_separates_distinct_cards_from_saved_copies() = runTest {
        val owned = entryNamed("Furret")
        val secondCopy = owned.copy(
            item = owned.item.copy(id = "item-Furret-second", quantity = 3),
        )
        val anotherCard = entryNamed("Crobat")
        val vm = CollectionViewModel(
            FakeCollectionRepository(listOf(owned, secondCopy, anotherCard)),
            FakeCardRepository(SetCatalogResult.Unavailable),
        )
        advanceUntilIdle()

        assertEquals(2, vm.state.value.distinctOwnedCards)
        assertEquals(5, vm.state.value.savedCopies)
    }

    @Test fun global_catalog_loading_never_exceeds_two_active_requests() = runTest {
        val collection = listOf(
            entryForSet("swsh3", "Escuridão Incandescente"),
            entryForSet("sv1", "Fábulas Nebulosas"),
            entryForSet("sv2", "Evoluções em Paldea"),
        )
        val cardRepository = BlockingCardRepository(collection.map { it.card.setId })
        val vm = CollectionViewModel(FakeCollectionRepository(collection), cardRepository)

        vm.onCardsLens(CardsLens.MISSING)
        runCurrent()

        assertEquals(2, cardRepository.started.size)
        assertEquals(2, cardRepository.maxActive)

        cardRepository.gates.getValue("swsh3").complete(SetCatalogResult.Unavailable)
        runCurrent()

        assertEquals(3, cardRepository.started.size)
        assertEquals(2, cardRepository.maxActive)
    }

    @Test fun global_overview_tracks_started_sets_added_and_removed_from_collection() = runTest {
        val first = entryForSet("swsh3", "Escuridão Incandescente")
        val second = entryForSet("sv1", "Fábulas Nebulosas")
        val repository = FakeCollectionRepository(listOf(first))
        val cardRepository = CatalogMapRepository(
            mapOf(
                "swsh3" to SetCatalogResult.Complete(SetCatalog("swsh3", listOf(first.card))),
                "sv1" to SetCatalogResult.Complete(SetCatalog("sv1", listOf(second.card))),
            ),
        )
        val vm = CollectionViewModel(repository, cardRepository)
        vm.onCardsLens(CardsLens.MISSING)
        advanceUntilIdle()
        assertEquals(listOf("swsh3"), cardRepository.requested)

        repository.entries.value = listOf(first, second)
        advanceUntilIdle()
        assertEquals(listOf("swsh3", "sv1"), cardRepository.requested)
        assertEquals(2, (vm.state.value.globalMissing as GlobalMissingState.Complete).verified.size)

        repository.entries.value = listOf(first)
        advanceUntilIdle()
        assertEquals(1, (vm.state.value.globalMissing as GlobalMissingState.Complete).verified.size)
    }

    private class BlockingCardRepository(setIds: List<String>) : CardRepository {
        val gates = setIds.associateWith { CompletableDeferred<SetCatalogResult>() }
        val started = mutableListOf<String>()
        var active = 0
        var maxActive = 0

        override suspend fun searchByName(name: String): List<Card> = emptyList()
        override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> = emptyList()
        override suspend fun getCard(id: String): Card? = null
        override suspend fun getSetCatalog(setId: String): SetCatalogResult {
            started += setId
            active++
            maxActive = maxOf(maxActive, active)
            return try {
                gates.getValue(setId).await()
            } finally {
                active--
            }
        }

        override suspend fun refreshPrices(cardIds: List<String>): PriceRefreshReport = error("unused")
    }

    private class CatalogMapRepository(
        private val catalogs: Map<String, SetCatalogResult>,
    ) : CardRepository {
        val requested = mutableListOf<String>()
        override suspend fun searchByName(name: String): List<Card> = emptyList()
        override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> = emptyList()
        override suspend fun getCard(id: String): Card? = null
        override suspend fun getSetCatalog(setId: String): SetCatalogResult {
            requested += setId
            return catalogs.getValue(setId)
        }

        override suspend fun refreshPrices(cardIds: List<String>): PriceRefreshReport = error("unused")
    }

    private class FakeCardRepository(
        var result: SetCatalogResult,
    ) : CardRepository {
        var catalogRequests = 0
        override suspend fun searchByName(name: String): List<Card> = emptyList()
        override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> = emptyList()
        override suspend fun getCard(id: String): Card? = null
        override suspend fun getSetCatalog(setId: String): SetCatalogResult {
            catalogRequests++
            return result
        }

        override suspend fun refreshPrices(cardIds: List<String>): PriceRefreshReport = error("unused")
    }

    private class FakeCollectionRepository(
        initial: List<CollectionEntry>,
        private val initialEmissionDelayMs: Long = 0,
    ) : CollectionRepository {
        val entries = MutableStateFlow(initial)

        override fun observe(): Flow<List<CollectionEntry>> = if (initialEmissionDelayMs == 0L) entries else flow {
            delay(initialEmissionDelayMs)
            emitAll(entries)
        }

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

    private fun entryPricedAt(market: Double, qty: Int) = CollectionEntry(
        item = CollectionItem(
            id = "item-furret",
            cardId = "card-Furret",
            quantity = qty,
            condition = Condition.NEAR_MINT,
            variant = CardVariant.NORMAL,
            pricePaid = null,
            addedAt = 0L,
        ),
        card = card("Furret", listOf(price(PriceSource.TCGPLAYER, CardVariant.NORMAL, market))),
    )

    private fun entryNamed(
        name: String,
        variant: CardVariant = CardVariant.NORMAL,
        condition: Condition = Condition.NEAR_MINT,
    ) = CollectionEntry(
        item = CollectionItem(
            id = "item-$name",
            cardId = "card-$name",
            quantity = 1,
            condition = condition,
            variant = variant,
            pricePaid = null,
            addedAt = 0L,
        ),
        card = card(name, emptyList()),
    )

    private fun entryForSet(setId: String, setName: String) = entryNamed("card-$setId").let { entry ->
        entry.copy(card = entry.card.copy(setId = setId, setName = setName))
    }

    private fun card(name: String, prices: List<CardPrice>) = Card(
        id = "card-$name",
        name = name,
        setId = "swsh3",
        setName = "Escuridão Incandescente",
        collectorNumber = "136",
        rarity = "Incomum",
        hp = 110,
        types = listOf("Incolor"),
        attacks = emptyList(),
        imageUrl = null,
        variants = setOf(CardVariant.NORMAL),
        prices = prices,
    )

    private fun price(source: PriceSource, variant: CardVariant, market: Double) = CardPrice(
        source = source,
        variant = variant,
        currency = Currency.USD,
        market = market,
        low = null, mid = null, high = null, trend = null,
        avg1 = null, avg7 = null, avg30 = null, updatedAt = null,
    )
}
