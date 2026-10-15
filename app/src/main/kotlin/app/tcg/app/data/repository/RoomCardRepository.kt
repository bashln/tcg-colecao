package app.tcg.app.data.repository

import app.tcg.app.data.local.CardDao
import app.tcg.app.data.local.toCard
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.SetCatalogResult
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
            remote.forEach { cardDao.cacheCard(it) }
            return remote
        }
        return cardDao.searchByName(name).map { it.toCard(cardDao.pricesFor(it.id)) }
    }

    override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> {
        val remote = fetchOrNull { source.searchByNumber(setId, collectorNumber) }
        if (remote != null) {
            remote.forEach { cardDao.cacheCard(it) }
            return remote
        }
        return cardDao.searchByNumber(collectorNumber, setId).map { it.toCard(cardDao.pricesFor(it.id)) }
    }

    override suspend fun searchByNumbers(collectorNumbers: Set<String>): List<Card> {
        val remote = fetchOrNull { source.searchByNumbers(collectorNumbers) }
        if (remote != null) {
            remote.forEach { cardDao.cacheCard(it) }
            return remote
        }
        return collectorNumbers.flatMap { number ->
            val stripped = number.trimStart('0').ifEmpty { "0" }
            (listOf(number, stripped) + if (number.startsWith('0')) emptyList() else listOf(number.padStart(3, '0')))
                .distinct()
                .flatMap { cardDao.searchByNumber(it, null) }
        }.distinctBy { it.id }.map { it.toCard(cardDao.pricesFor(it.id)) }
    }

    override suspend fun getCard(id: String): Card? {
        val cached = cardDao.getById(id)?.toCard(cardDao.pricesFor(id))
        // Search results cache a brief (no attacks, no prices), so always refresh from the full
        // endpoint and replace the cache; fall back to the cached copy only if the network fails.
        val remote = fetchOrNull { source.getCard(id) }
        if (remote == null) return cached
        cardDao.captureCardPrices(remote, System.currentTimeMillis())
        return remote
    }

    override suspend fun getSetCatalog(setId: String): SetCatalogResult {
        val catalog = fetchOrNull { source.getSetCatalog(setId) } ?: return SetCatalogResult.Unavailable
        if (catalog.cards.isEmpty()) return SetCatalogResult.Unavailable
        return SetCatalogResult.Complete(catalog)
    }

    private suspend fun <T> fetchOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
