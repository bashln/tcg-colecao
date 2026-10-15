package app.tcg.app.testutil

import app.tcg.core.data.repository.CardListRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardList
import app.tcg.core.domain.model.CardListDetail
import app.tcg.core.domain.model.CardListKind
import app.tcg.core.domain.model.CardListSummary
import app.tcg.core.domain.model.ListAddition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

object FakeCardListRepository : CardListRepository {
    override fun observeLists(): Flow<List<CardListSummary>> = flowOf(emptyList())

    override fun observeList(listId: String): Flow<CardListDetail?> = flowOf(null)

    override suspend fun create(name: String, kind: CardListKind): CardList = error("unused")

    override suspend fun addCards(cards: List<Card>, listIds: Set<String>): ListAddition = error("unused")

    override suspend fun removeCard(listId: String, cardId: String) = error("unused")

    override suspend fun rename(listId: String, name: String) = error("unused")

    override suspend fun delete(listId: String) = error("unused")
}
