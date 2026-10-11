package app.tcg.app.ui.collection

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.SetCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionOwnershipTest {

    @Test
    fun empty_collection_has_no_started_sets() {
        assertEquals(GlobalMissingState.NoStartedSets, buildGlobalMissingState(emptyList(), emptyMap()))
    }

    @Test
    fun started_sets_are_not_reported_as_missing_before_catalog_check() {
        val saved = entry(card("swsh3-136", "Furret"))

        assertEquals(
            GlobalMissingState.NotChecked(listOf(StartedSet("swsh3", "Escuridão Incandescente", setOf("swsh3-136"), 1))),
            buildGlobalMissingState(listOf(saved), emptyMap()),
        )
    }

    @Test
    fun complete_catalog_counts_distinct_owned_ids_and_reports_missing_cards() {
        val furret = card("swsh3-136", "Furret")
        val duplicateCopy = entry(furret, itemId = "another-copy", quantity = 4)
        val crobat = card("swsh3-137", "Crobat")

        val result = buildGlobalMissingState(
            listOf(entry(furret), duplicateCopy),
            mapOf("swsh3" to CatalogLoad.Verified(SetCatalog("swsh3", listOf(furret, crobat)))),
        )

        assertEquals(
            GlobalMissingState.Complete(
                listOf(
                    MissingGroup(
                        StartedSet("swsh3", "Escuridão Incandescente", setOf("swsh3-136"), 5),
                        cards = listOf(crobat),
                        savedIdsOutsideCatalog = emptySet(),
                    ),
                ),
            ),
            result,
        )
        assertEquals(5, (result as GlobalMissingState.Complete).verified.single().set.savedCopies)
    }

    @Test
    fun partial_catalog_with_no_confirmed_missing_stays_partial_and_reports_saved_id_mismatch() {
        val saved = entry(card("swsh3-999", "Card fora do catálogo"))
        val unavailableSetCard = entry(
            card("sv1-042", "Carta salva sem catálogo", setId = "sv1"),
            itemId = "item-sv1",
        )
        val listed = card("swsh3-001", "Card catalogada")

        val result = buildGlobalMissingState(
            listOf(saved, unavailableSetCard),
            mapOf(
                "swsh3" to CatalogLoad.Verified(SetCatalog("swsh3", listOf(listed))),
                "sv1" to CatalogLoad.Unavailable,
            ),
        )

        assertEquals(
            GlobalMissingState.Partial(
                verified = listOf(
                    MissingGroup(
                        StartedSet("swsh3", "Escuridão Incandescente", setOf("swsh3-999"), 1),
                        cards = listOf(listed),
                        savedIdsOutsideCatalog = setOf("swsh3-999"),
                    ),
                ),
                pending = emptyList(),
                failed = listOf(StartedSet("sv1", "Fábulas Nebulosas", setOf("sv1-042"), 1)),
            ),
            result,
        )
    }

    @Test
    fun unavailable_catalog_never_produces_missing_cards() {
        val saved = entry(card("swsh3-136", "Furret"))

        assertEquals(
            GlobalMissingState.Unavailable(listOf(StartedSet("swsh3", "Escuridão Incandescente", setOf("swsh3-136"), 1))),
            buildGlobalMissingState(listOf(saved), mapOf("swsh3" to CatalogLoad.Unavailable)),
        )
    }

    private fun entry(card: Card, itemId: String = "item-${card.id}", quantity: Int = 1) = CollectionEntry(
        item = CollectionItem(itemId, card.id, quantity, Condition.NEAR_MINT, CardVariant.NORMAL, null, 0L),
        card = card,
    )

    private fun card(id: String, name: String, setId: String = "swsh3") = Card(
        id = id,
        name = name,
        setId = setId,
        setName = if (setId == "sv1") "Fábulas Nebulosas" else "Escuridão Incandescente",
        collectorNumber = id.substringAfterLast('-'),
        rarity = null,
        hp = null,
        types = emptyList(),
        attacks = emptyList(),
        imageUrl = null,
        variants = setOf(CardVariant.NORMAL),
        prices = emptyList(),
    )
}
