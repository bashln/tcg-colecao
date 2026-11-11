package app.tcg.app.data.repository

import app.tcg.app.data.local.CardDao
import app.tcg.app.data.local.CardPriceEntity
import app.tcg.app.data.local.toCard
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.PriceRefreshReport
import app.tcg.core.data.repository.SetCatalogResult
import app.tcg.core.data.tcgdex.TcgdexCardSource
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.valuation.Valuation
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

    override suspend fun refreshPrices(cardIds: List<String>): PriceRefreshReport {
        val ids = cardIds.distinct()
        var refreshed = 0
        var unavailable = 0
        for (id in ids) {
            val stored = try {
                refreshOne(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (stored) refreshed++ else unavailable++
        }
        return PriceRefreshReport(requested = ids.size, refreshed = refreshed, unavailable = unavailable)
    }

    override suspend fun saveCardPrice(
        cardId: String,
        source: PriceSource,
        variant: CardVariant,
        marketPrice: Double,
    ) {
        val currency = Valuation.currencyOf(source)
        val entity = CardPriceEntity(
            cardId = cardId,
            source = source,
            variant = variant,
            currency = currency,
            market = marketPrice,
            low = null,
            mid = marketPrice,
            high = null,
            trend = null,
            avg1 = null,
            avg7 = null,
            avg30 = null,
            updatedAt = java.time.Instant.now().toString(),
        )
        cardDao.saveSinglePrice(entity, System.currentTimeMillis())
    }

    private suspend fun refreshOne(id: String): Boolean {
        val card = fetchOrNull { source.getCard(id) } ?: return false
        cardDao.captureCardPrices(card, System.currentTimeMillis())
        return true
    }

    private suspend fun <T> fetchOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
