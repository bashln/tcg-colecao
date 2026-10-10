package app.tcg.core.data.repository

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Money
import kotlinx.coroutines.flow.Flow

interface CardRepository {
    suspend fun searchByName(name: String): List<Card>
    suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card>
    suspend fun getCard(id: String): Card?
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

    /** Emits the current collection and re-emits whenever it changes. */
    fun observe(): Flow<List<CollectionEntry>>

    suspend fun remove(itemId: String)
}
