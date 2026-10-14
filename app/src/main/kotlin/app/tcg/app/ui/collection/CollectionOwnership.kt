package app.tcg.app.ui.collection

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.SetCatalog

sealed interface CatalogLoad {
    data object Queued : CatalogLoad
    data object Loading : CatalogLoad
    data class Verified(val catalog: SetCatalog) : CatalogLoad
    data object Unavailable : CatalogLoad
}

data class StartedSet(
    val id: String,
    val name: String,
    val ownedCardIds: Set<String>,
    val savedCopies: Int,
)

data class MissingGroup(
    val set: StartedSet,
    val cards: List<Card>,
    val savedIdsOutsideCatalog: Set<String>,
)

sealed interface GlobalMissingState {
    data object NoStartedSets : GlobalMissingState
    data class NotChecked(val sets: List<StartedSet>) : GlobalMissingState
    data class Loading(val pending: List<StartedSet>, val failed: List<StartedSet>) : GlobalMissingState
    data class Unavailable(val failed: List<StartedSet>) : GlobalMissingState
    data class Partial(
        val verified: List<MissingGroup>,
        val pending: List<StartedSet>,
        val failed: List<StartedSet>,
    ) : GlobalMissingState
    data class Complete(val verified: List<MissingGroup>) : GlobalMissingState
}

fun buildGlobalMissingState(
    entries: List<CollectionEntry>,
    catalogLoads: Map<String, CatalogLoad>,
): GlobalMissingState {
    val started = entries.groupBy { it.card.setId }
        .map { (setId, setEntries) ->
            StartedSet(
                id = setId,
                name = setEntries.first().card.setName.ifBlank { setId },
                ownedCardIds = setEntries.mapTo(linkedSetOf()) { it.card.id },
                savedCopies = setEntries.sumOf { it.item.quantity },
            )
        }
        .sortedBy { it.name.lowercase() }

    if (started.isEmpty()) return GlobalMissingState.NoStartedSets

    val verified = mutableListOf<MissingGroup>()
    val pending = mutableListOf<StartedSet>()
    val failed = mutableListOf<StartedSet>()
    val unchecked = mutableListOf<StartedSet>()

    started.forEach { set ->
        when (val load = catalogLoads[set.id]) {
            null -> unchecked += set
            CatalogLoad.Queued, CatalogLoad.Loading -> pending += set
            CatalogLoad.Unavailable -> failed += set
            is CatalogLoad.Verified -> {
                if (load.catalog.setId != set.id) {
                    failed += set
                } else {
                    val catalogIds = load.catalog.cards.mapTo(hashSetOf()) { it.id }
                    verified += MissingGroup(
                        set = set,
                        cards = load.catalog.cards.filterNot { it.id in set.ownedCardIds },
                        savedIdsOutsideCatalog = set.ownedCardIds - catalogIds,
                    )
                }
            }
        }
    }

    if (verified.isEmpty() && pending.isEmpty() && failed.isEmpty()) {
        return GlobalMissingState.NotChecked(unchecked)
    }
    if (unchecked.isNotEmpty()) {
        pending += unchecked
    }
    if (verified.isEmpty()) {
        return if (pending.isNotEmpty()) GlobalMissingState.Loading(pending, failed)
        else GlobalMissingState.Unavailable(failed)
    }
    return if (pending.isEmpty() && failed.isEmpty()) {
        GlobalMissingState.Complete(verified)
    } else {
        GlobalMissingState.Partial(verified, pending, failed)
    }
}
