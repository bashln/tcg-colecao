package app.tcg.core.data.repository

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardList
import app.tcg.core.domain.model.CardListDetail
import app.tcg.core.domain.model.CardListKind
import app.tcg.core.domain.model.CardListSummary
import app.tcg.core.domain.model.ListAddition
import kotlinx.coroutines.flow.Flow

interface CardListRepository {
    fun observeLists(): Flow<List<CardListSummary>>
    fun observeList(listId: String): Flow<CardListDetail?>
    suspend fun create(name: String, kind: CardListKind): CardList
    suspend fun addCards(cards: List<Card>, listIds: Set<String>): ListAddition
    suspend fun removeCard(listId: String, cardId: String)
    suspend fun rename(listId: String, name: String)
    suspend fun delete(listId: String)
}
