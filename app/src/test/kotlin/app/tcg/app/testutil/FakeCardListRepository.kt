package app.tcg.app.testutil

import app.tcg.core.data.repository.CardListRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardList
import app.tcg.core.domain.model.CardListDetail
import app.tcg.core.domain.model.CardListKind
import app.tcg.core.domain.model.CardListSummary
import app.tcg.core.domain.model.ListAddition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory [CardListRepository] for ViewModel tests; every write fails while [failure] is set. */
class FakeCardListRepository(initial: List<CardListSummary> = emptyList()) : CardListRepository {

    val lists = MutableStateFlow(initial)
    val details = MutableStateFlow(
        initial.associate { it.list.id to CardListDetail(it.list, emptyList()) },
    )

    var failure: Exception? = null

    val created = mutableListOf<Pair<String, CardListKind>>()
    val added = mutableListOf<Pair<List<Card>, Set<String>>>()
    val removed = mutableListOf<Pair<String, String>>()
    val renamed = mutableListOf<Pair<String, String>>()
    val deleted = mutableListOf<String>()

    private var nextId = 0

    override fun observeLists(): Flow<List<CardListSummary>> = lists

    override fun observeList(listId: String): Flow<CardListDetail?> = details.map { it[listId] }

    override suspend fun create(name: String, kind: CardListKind): CardList {
        failIfConfigured()
        created += name to kind
        val list = CardList(id = "list-${nextId++}", name = name, kind = kind, createdAt = 0L)
        lists.update { it + CardListSummary(list, 0) }
        details.update { it + (list.id to CardListDetail(list, emptyList())) }
        return list
    }

    override suspend fun addCards(cards: List<Card>, listIds: Set<String>): ListAddition {
        failIfConfigured()
        added += cards to listIds
        val incoming = cards.distinctBy(Card::id)
        var newMemberships = 0
        val updated = details.value.mapValues { (id, detail) ->
            if (id !in listIds) {
                detail
            } else {
                val existing = detail.cards.map(Card::id).toSet()
                val fresh = incoming.filterNot { it.id in existing }
                newMemberships += fresh.size
                detail.copy(cards = detail.cards + fresh)
            }
        }
        details.value = updated
        lists.update { summaries ->
            summaries.map { summary ->
                if (summary.list.id in listIds) {
                    val detail = updated[summary.list.id]
                    summary.copy(cardCount = detail?.cards?.size ?: summary.cardCount)
                } else {
                    summary
                }
            }
        }
        return ListAddition(newMemberships, incoming.size * listIds.size - newMemberships)
    }

    override suspend fun removeCard(listId: String, cardId: String) {
        failIfConfigured()
        removed += listId to cardId
        details.update { current ->
            current[listId]?.let { detail ->
                current + (listId to detail.copy(cards = detail.cards.filterNot { it.id == cardId }))
            } ?: current
        }
    }

    override suspend fun rename(listId: String, name: String) {
        failIfConfigured()
        renamed += listId to name
        lists.update { summaries ->
            summaries.map { summary ->
                if (summary.list.id == listId) summary.copy(list = summary.list.copy(name = name)) else summary
            }
        }
    }

    override suspend fun delete(listId: String) {
        failIfConfigured()
        deleted += listId
        lists.update { summaries -> summaries.filterNot { it.list.id == listId } }
        details.update { it - listId }
    }

    private fun failIfConfigured() {
        failure?.let { throw it }
    }
}
