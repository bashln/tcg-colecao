package app.tcg.core.data.repository

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.CollectionItemChanges
import app.tcg.core.domain.model.CollectionItemDetails
import app.tcg.core.domain.model.CollectionUpdate
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.CurrentHoldingsHistory
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.model.SetCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDate

interface CardRepository {
    suspend fun searchByName(name: String): List<Card>
    suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card>
    suspend fun searchByNumbers(collectorNumbers: Set<String>): List<Card> =
        collectorNumbers.flatMap { searchByNumber(null, it) }.distinctBy(Card::id)
    suspend fun getCard(id: String): Card?
    suspend fun getSetCatalog(setId: String): SetCatalogResult
}

sealed interface SetCatalogResult {
    data class Complete(val catalog: SetCatalog) : SetCatalogResult
    data object Unavailable : SetCatalogResult
}

interface CollectionRepository {
    suspend fun add(
        card: Card,
        variant: CardVariant,
        condition: Condition,
        quantity: Int,
        pricePaid: Money?,
    ): CollectionItem

    suspend fun list(): List<CollectionEntry>

    suspend fun getById(itemId: String): CollectionItemDetails?

    suspend fun update(original: CollectionItem, changes: CollectionItemChanges): CollectionUpdate

    /** Emits the current collection and re-emits whenever it changes. */
    fun observe(): Flow<List<CollectionEntry>>

    fun observeCurrentHoldingsHistory(source: PriceSource, asOfUtcDay: LocalDate): Flow<CurrentHoldingsHistory> =
        flow { error("Price history is unavailable in this repository") }

    suspend fun remove(itemId: String)
}
