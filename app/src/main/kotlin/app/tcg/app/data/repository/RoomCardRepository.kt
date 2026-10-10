package app.tcg.app.data.repository

import app.tcg.app.data.local.CardDao
import app.tcg.app.data.local.toCard
import app.tcg.app.data.local.toEntity
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.tcgdex.TcgdexCardSource
import app.tcg.core.domain.model.Card
import kotlinx.coroutines.CancellationException

class RoomCardRepository(
    private val cardDao: CardDao,
    private val source: TcgdexCardSource,
) : CardRepository {

    override suspend fun searchByName(name: String): List<Card> {
        val remote = fetchOrNull { source.searchByName(name) }
        if (remote != null) {
            remote.forEach { persist(it) }
            return remote
        }
        return cardDao.searchByName(name).map { it.toCard(cardDao.pricesFor(it.id)) }
    }

    override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> {
        val remote = fetchOrNull { source.searchByNumber(setId, collectorNumber) }
        if (remote != null) {
            remote.forEach { persist(it) }
            return remote
        }
        return cardDao.searchByNumber(collectorNumber, setId).map { it.toCard(cardDao.pricesFor(it.id)) }
    }

    override suspend fun getCard(id: String): Card? {
        val cached = cardDao.getById(id)?.toCard(cardDao.pricesFor(id))
        // Search results cache a brief (no attacks, no prices), so always refresh from the full
        // endpoint and replace the cache; fall back to the cached copy only if the network fails.
        val remote = fetchOrNull { source.getCard(id) }
        if (remote == null) return cached
        persist(remote)
        return remote
    }

    private suspend fun <T> fetchOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun persist(card: Card) {
        cardDao.upsert(card.toEntity())
        cardDao.deletePricesFor(card.id)
        cardDao.upsertPrices(card.prices.map { it.toEntity(card.id) })
    }
}
