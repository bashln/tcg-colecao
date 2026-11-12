package app.tcg.app.data.repository

import androidx.room.withTransaction
import app.tcg.app.data.local.CardDao
import app.tcg.app.data.local.CardListDao
import app.tcg.app.data.local.CardListEntity
import app.tcg.app.data.local.CardListMemberEntity
import app.tcg.app.data.local.TcgDatabase
import app.tcg.app.data.local.toCard
import app.tcg.app.data.local.toEntity
import app.tcg.app.data.local.toList
import app.tcg.app.data.local.toSummary
import app.tcg.core.data.repository.CardListRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardList
import app.tcg.core.domain.model.CardListDetail
import app.tcg.core.domain.model.CardListKind
import app.tcg.core.domain.model.CardListSummary
import app.tcg.core.domain.model.ListAddition
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class RoomCardListRepository(
    private val database: TcgDatabase,
    private val lists: CardListDao,
    private val cards: CardDao,
) : CardListRepository {
    override fun observeLists(): Flow<List<CardListSummary>> =
        lists.observeLists().map { rows -> rows.map { it.toSummary() } }

    override fun observeList(listId: String): Flow<CardListDetail?> =
        combine(lists.observeList(listId), lists.observeCards(listId)) { list, cardRows ->
            list?.let { cardList ->
                CardListDetail(cardList.toList(), cardRows.map { row -> row.toCard(cards.pricesFor(row.id)) })
            }
        }

    override suspend fun create(name: String, kind: CardListKind): CardList {
        val list = CardList(UUID.randomUUID().toString(), validName(name), kind, System.currentTimeMillis())
        lists.insertList(CardListEntity(list.id, list.name, list.kind, list.createdAt))
        return list
    }

    override suspend fun addCards(cards: List<Card>, listIds: Set<String>): ListAddition {
        val uniqueCards = cards.distinctBy(Card::id)
        require(uniqueCards.isNotEmpty() && listIds.isNotEmpty())
        return database.withTransaction {
            require(lists.existingIds(listIds).toSet() == listIds) { "Uma das listas não existe mais." }
            uniqueCards.forEach { card ->
                this@RoomCardListRepository.cards.insertIfAbsent(card.toEntity())
            }
            val addedAt = System.currentTimeMillis()
            var added = 0
            listIds.forEach { listId ->
                uniqueCards.forEach { card ->
                    if (lists.insertMember(CardListMemberEntity(listId, card.id, addedAt)) != -1L) added++
                }
            }
            ListAddition(added, uniqueCards.size * listIds.size - added)
        }
    }

    override suspend fun removeCard(listId: String, cardId: String) {
        lists.removeCard(listId, cardId)
    }

    override suspend fun rename(listId: String, name: String) {
        require(lists.rename(listId, validName(name)) == 1) { "Lista não encontrada." }
    }

    override suspend fun delete(listId: String) {
        require(lists.delete(listId) == 1) { "Lista não encontrada." }
    }

    private fun validName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= 60) { "Use um nome entre 1 e 60 caracteres." }
    }
}
