package app.tcg.app.data.repository

import app.tcg.app.data.local.CardDao
import app.tcg.app.data.local.toCard
import app.tcg.app.data.local.toEntity
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.tcgdex.TcgdexCardSource
import app.tcg.core.domain.model.Card

class RoomCardRepository(
    private val cardDao: CardDao,
    private val source: TcgdexCardSource,
) : CardRepository {

    override suspend fun searchByName(name: String): List<Card> {
        val cached = cardDao.searchByName(name).map { it.toCard(cardDao.pricesFor(it.id)) }
        if (cached.isNotEmpty()) return cached
        return source.searchByName(name).also { cards -> cards.forEach { persist(it) } }
    }

    override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> {
        val cached = cardDao.searchByNumber(collectorNumber, setId).map { it.toCard(cardDao.pricesFor(it.id)) }
        if (cached.isNotEmpty()) return cached
        return source.searchByNumber(setId, collectorNumber).also { cards -> cards.forEach { persist(it) } }
    }

    override suspend fun getCard(id: String): Card? {
        cardDao.getById(id)?.let { return it.toCard(cardDao.pricesFor(id)) }
        val remote = source.getCard(id) ?: return null
        persist(remote)
        return remote
    }

    private suspend fun persist(card: Card) {
        cardDao.upsert(card.toEntity())
        cardDao.deletePricesFor(card.id)
        cardDao.upsertPrices(card.prices.map { it.toEntity(card.id) })
    }
}
