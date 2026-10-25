package app.tcg.app.ui.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.data.repository.SetCatalogResult
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.model.SetCatalog
import app.tcg.core.domain.valuation.Valuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CollectionSection { CARDS, SETS }

enum class OwnershipFilter { ALL, OWNED, MISSING }

enum class CardsLens { OWNED, MISSING }

data class CollectionCardFilters(
    val query: String = "",
    val variant: CardVariant? = null,
    val condition: Condition? = null,
)

data class OwnedSetSummary(
    val id: String,
    val name: String,
    val ownedCards: Int,
)

data class SetCardRow(val card: Card, val ownedQuantity: Int) {
    val isOwned: Boolean get() = ownedQuantity > 0
}

sealed interface SelectedSetState {
    data object Closed : SelectedSetState
    data class Loading(val setId: String) : SelectedSetState
    data class Ready(val catalog: SetCatalog) : SelectedSetState
    data class Unavailable(val setId: String) : SelectedSetState
}

data class CollectionUiState(
    val entries: List<CollectionEntry> = emptyList(),
    val query: String = "",
    val variantFilter: CardVariant? = null,
    val conditionFilter: Condition? = null,
    val ownedVariants: List<CardVariant> = emptyList(),
    val ownedConditions: List<Condition> = emptyList(),
    val total: Money = Money(0.0, Currency.USD),
    val loading: Boolean = false,
    val section: CollectionSection = CollectionSection.CARDS,
    val ownedSets: List<OwnedSetSummary> = emptyList(),
    val selectedSet: SelectedSetState = SelectedSetState.Closed,
    val selectedSetName: String = "",
    val ownershipFilter: OwnershipFilter = OwnershipFilter.ALL,
    val ownedSetEntries: List<CollectionEntry> = emptyList(),
    val setCards: List<SetCardRow> = emptyList(),
    val cardsLens: CardsLens = CardsLens.OWNED,
    val globalMissing: GlobalMissingState = GlobalMissingState.NoStartedSets,
    val distinctOwnedCards: Int = 0,
    val savedCopies: Int = 0,
)

class CollectionViewModel(
    private val repository: CollectionRepository,
    private val cardRepository: CardRepository,
) : ViewModel() {

    private val filters = MutableStateFlow(CollectionCardFilters())
    private val section = MutableStateFlow(CollectionSection.CARDS)
    private val cardsLens = MutableStateFlow(CardsLens.OWNED)
    private val selectedSet = MutableStateFlow<SelectedSetState>(SelectedSetState.Closed)
    private val ownershipFilter = MutableStateFlow(OwnershipFilter.ALL)
    private val catalogLoads = MutableStateFlow<Map<String, CatalogLoad>>(emptyMap())
    private val catalogJobs = mutableMapOf<String, Job>()
    private val catalogSemaphore = Semaphore(2)
    private val hasCollectionSnapshot = MutableStateFlow(false)
    private val entries = repository.observe().onEach { hasCollectionSnapshot.value = true }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )
    private val globalMissing = combine(entries, catalogLoads, ::buildGlobalMissingState)

    init {
        viewModelScope.launch {
            entries.collect { currentEntries ->
                if (cardsLens.value == CardsLens.MISSING) ensureGlobalCatalogs(currentEntries)
            }
        }
    }

    val state: StateFlow<CollectionUiState> =
        combine(entries, filters, section, selectedSet, ownershipFilter) {
                currentEntries, cardFilters, currentSection, currentSet, filter ->
            stateFor(currentEntries, cardFilters, currentSection, currentSet, filter)
        }.combine(cardsLens) { current, lens -> current.copy(cardsLens = lens) }
            .combine(globalMissing) { current, missing -> current.copy(globalMissing = missing) }
            .combine(hasCollectionSnapshot) { current, loaded -> current.copy(loading = !loaded) }
            .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = CollectionUiState(loading = true),
        )

    fun onQuery(text: String) {
        filters.update { it.copy(query = text) }
    }

    fun onVariantFilter(variant: CardVariant?) {
        filters.update { it.copy(variant = variant) }
    }

    fun onConditionFilter(condition: Condition?) {
        filters.update { it.copy(condition = condition) }
    }

    fun onClearCardFilters() {
        filters.update { it.copy(variant = null, condition = null) }
    }

    fun onSection(section: CollectionSection) {
        if (this.section.value != section) resetSearchAndFilters()
        selectedSet.value = SelectedSetState.Closed
        ownershipFilter.value = OwnershipFilter.ALL
        this.section.value = section
    }

    fun onCardsLens(lens: CardsLens) {
        if (cardsLens.value == lens) return
        resetSearchAndFilters()
        cardsLens.value = lens
        if (lens == CardsLens.MISSING) ensureGlobalCatalogs(entries.value)
    }

    private fun resetSearchAndFilters() {
        filters.value = CollectionCardFilters()
    }

    fun onSetSelected(setId: String) {
        section.value = CollectionSection.SETS
        ownershipFilter.value = OwnershipFilter.ALL
        val currentLoad = catalogLoads.value[setId]
        selectedSet.value = when (currentLoad) {
            is CatalogLoad.Verified -> SelectedSetState.Ready(currentLoad.catalog)
            else -> SelectedSetState.Loading(setId)
        }
        if (currentLoad !is CatalogLoad.Verified) {
            requestCatalog(setId, retry = currentLoad == CatalogLoad.Unavailable)
        }
    }

    fun onCloseSet() {
        selectedSet.value = SelectedSetState.Closed
    }

    fun onOwnershipFilter(filter: OwnershipFilter) {
        ownershipFilter.value = filter
    }

    fun onRetryCatalog() {
        val setId = (selectedSet.value as? SelectedSetState.Unavailable)?.setId ?: return
        selectedSet.value = SelectedSetState.Loading(setId)
        requestCatalog(setId, retry = true)
    }

    fun onRetryMissingCatalogs(setId: String? = null) {
        val failedIds = when (val missing = state.value.globalMissing) {
            is GlobalMissingState.Unavailable -> missing.failed.map { it.id }
            is GlobalMissingState.Partial -> missing.failed.map { it.id }
            else -> emptyList()
        }
        (setId?.let(::listOf) ?: failedIds).forEach { requestCatalog(it, retry = true) }
    }

    private fun stateFor(
        entries: List<CollectionEntry>,
        filters: CollectionCardFilters,
        section: CollectionSection,
        selectedSet: SelectedSetState,
        ownershipFilter: OwnershipFilter,
    ): CollectionUiState {
        val filteredEntries = entries.filter { entry ->
            (filters.variant == null || entry.item.variant == filters.variant) &&
                (filters.condition == null || entry.item.condition == filters.condition)
        }
        val sets = entries.groupBy { it.card.setId }
            .map { (id, setEntries) ->
                OwnedSetSummary(
                    id = id,
                    name = setEntries.first().card.setName.ifBlank { id },
                    ownedCards = setEntries.map { it.card.id }.distinct().size,
                )
            }
            .filter { it.name.contains(filters.query, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }

        val selectedId = when (selectedSet) {
            SelectedSetState.Closed -> null
            is SelectedSetState.Loading -> selectedSet.setId
            is SelectedSetState.Ready -> selectedSet.catalog.setId
            is SelectedSetState.Unavailable -> selectedSet.setId
        }
        val entriesForSet = entries.filter { it.card.setId == selectedId }
        val selectedName = sets.firstOrNull { it.id == selectedId }?.name
            ?: entriesForSet.firstOrNull()?.card?.setName.orEmpty()
        val quantitiesByCard = entriesForSet.groupBy { it.card.id }
            .mapValues { (_, cardEntries) -> cardEntries.sumOf { it.item.quantity } }
        val rows = (selectedSet as? SelectedSetState.Ready)?.catalog?.cards.orEmpty()
            .map { card -> SetCardRow(card, quantitiesByCard[card.id] ?: 0) }
            .filter { row ->
                when (ownershipFilter) {
                    OwnershipFilter.ALL -> true
                    OwnershipFilter.OWNED -> row.isOwned
                    OwnershipFilter.MISSING -> !row.isOwned
                }
            }

        return CollectionUiState(
            entries = filteredEntries.filter { it.card.name.contains(filters.query, ignoreCase = true) },
            query = filters.query,
            variantFilter = filters.variant,
            conditionFilter = filters.condition,
            ownedVariants = CardVariant.entries.filter { variant -> entries.any { it.item.variant == variant } },
            ownedConditions = Condition.entries.filter { condition -> entries.any { it.item.condition == condition } },
            total = Valuation.portfolioTotal(entries, PriceSource.TCGPLAYER),
            loading = false,
            section = section,
            ownedSets = sets,
            selectedSet = selectedSet,
            selectedSetName = selectedName,
            ownershipFilter = ownershipFilter,
            ownedSetEntries = entriesForSet,
            setCards = rows,
            distinctOwnedCards = entries.map { it.card.id }.distinct().size,
            savedCopies = entries.sumOf { it.item.quantity },
        )
    }

    private fun ensureGlobalCatalogs(entries: List<CollectionEntry>) {
        entries.asSequence().map { it.card.setId }.distinct().forEach { setId ->
            requestCatalog(setId)
        }
    }

    private fun requestCatalog(setId: String, retry: Boolean = false) {
        if (setId.isBlank() || catalogJobs.containsKey(setId)) return
        when (catalogLoads.value[setId]) {
            is CatalogLoad.Verified -> return
            CatalogLoad.Unavailable -> if (!retry) return
            CatalogLoad.Queued, CatalogLoad.Loading -> return
            null -> Unit
        }

        catalogLoads.value = catalogLoads.value + (setId to CatalogLoad.Queued)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                catalogSemaphore.withPermit {
                    catalogLoads.value = catalogLoads.value + (setId to CatalogLoad.Loading)
                    val result = try {
                        cardRepository.getSetCatalog(setId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        SetCatalogResult.Unavailable
                    }
                    val load = when (result) {
                        is SetCatalogResult.Complete -> if (result.catalog.setId == setId) {
                            CatalogLoad.Verified(result.catalog)
                        } else {
                            CatalogLoad.Unavailable
                        }
                        SetCatalogResult.Unavailable -> CatalogLoad.Unavailable
                    }
                    catalogLoads.value = catalogLoads.value + (setId to load)
                    if ((selectedSet.value as? SelectedSetState.Loading)?.setId == setId) {
                        selectedSet.value = when (load) {
                            is CatalogLoad.Verified -> SelectedSetState.Ready(load.catalog)
                            else -> SelectedSetState.Unavailable(setId)
                        }
                    }
                }
            } finally {
                catalogJobs.remove(setId)
            }
        }
        catalogJobs[setId] = job
        job.start()
    }
}
