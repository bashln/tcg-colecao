package app.tcg.app.data.repository

import app.tcg.app.data.local.CardDao
import app.tcg.app.data.local.CardPriceEntity
import app.tcg.app.data.local.toCard
import app.tcg.app.data.local.toEntity
import app.tcg.app.data.local.toPrice
import app.tcg.core.data.jornada.DEFAULT_MAX_AGE_HOURS
import app.tcg.core.data.jornada.JornadaCardRequest
import app.tcg.core.data.jornada.JornadaQuoteSource
import app.tcg.core.data.jornada.toCardPrices
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
    private val jornada: JornadaQuoteSource? = null,
) : CardRepository {

    override suspend fun searchByName(name: String): List<Card> {
        val remote = fetchOrNull { source.searchByName(name) }
        if (remote != null) {
            remote.forEach { cardDao.cacheCard(it) }
            return remote.map { it.withCachedQuotes() }
        }
        return cardDao.searchByName(name).map { it.toCard(cardDao.pricesFor(it.id)) }
    }

    override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> {
        val remote = fetchOrNull { source.searchByNumber(setId, collectorNumber) }
        if (remote != null) {
            remote.forEach { cardDao.cacheCard(it) }
            return remote.map { it.withCachedQuotes() }
        }
        return cardDao.searchByNumber(collectorNumber, setId).map { it.toCard(cardDao.pricesFor(it.id)) }
    }

    override suspend fun searchByNumbers(collectorNumbers: Set<String>): List<Card> {
        val remote = fetchOrNull { source.searchByNumbers(collectorNumbers) }
        if (remote != null) {
            remote.forEach { cardDao.cacheCard(it) }
            return remote.map { it.withCachedQuotes() }
        }
        return collectorNumbers.flatMap { number ->
            val stripped = number.trimStart('0').ifEmpty { "0" }
            (listOf(number, stripped) + if (number.startsWith('0')) emptyList() else listOf(number.padStart(3, '0')))
                .distinct()
                .flatMap { cardDao.searchByNumber(it, null) }
        }.distinctBy { it.id }.map { it.toCard(cardDao.pricesFor(it.id)) }
    }

    /**
     * The catalog answer carries only the market feeds. Local quotes (Jornada, a manually typed
     * Liga price) live in the cache and must ride along, or the detail screen shows them empty.
     */
    private suspend fun Card.withCachedQuotes(): Card {
        val extra = cardDao.pricesFor(id)
            .filter { it.source != PriceSource.TCGPLAYER && it.source != PriceSource.CARDMARKET }
            .map { it.toPrice() }
        if (extra.isEmpty()) return this
        return copy(prices = (prices + extra).distinctBy { it.source to it.variant })
    }

    override suspend fun getCard(id: String): Card? {
        val cached = cardDao.getById(id)?.toCard(cardDao.pricesFor(id))
        // Search results cache a brief (no attacks, no prices), so always refresh from the full
        // endpoint and replace the cache; fall back to the cached copy only if the network fails.
        val remote = fetchOrNull { source.getCard(id) }
        if (remote == null) return cached
        cardDao.captureCardPrices(remote, System.currentTimeMillis())
        return remote.withCachedQuotes()
    }

    override suspend fun getSetCatalog(setId: String): SetCatalogResult {
        val catalog = fetchOrNull { source.getSetCatalog(setId) } ?: return SetCatalogResult.Unavailable
        if (catalog.cards.isEmpty()) return SetCatalogResult.Unavailable
        return SetCatalogResult.Complete(catalog)
    }

    override suspend fun refreshPrices(cardIds: List<String>): PriceRefreshReport {
        val ids = cardIds.distinct()
        val refreshed = mutableSetOf<String>()
        for (id in ids) {
            val stored = try {
                refreshOne(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (stored) refreshed += id
        }
        refreshed += refreshJornada(ids)
        return PriceRefreshReport(
            requested = ids.size,
            refreshed = refreshed.size,
            unavailable = ids.size - refreshed.size,
        )
    }

    private suspend fun refreshJornada(ids: List<String>): Set<String> {
        val priceSource = jornada ?: return emptySet()
        val cards = ids.mapNotNull { cardDao.getById(it) }
        if (cards.isEmpty()) return emptySet()
        val response = try {
            priceSource.quotes(
                cards.map { card ->
                    JornadaCardRequest(
                        cardId = card.id,
                        setId = card.setId,
                        collectorNumber = card.collectorNumber,
                        name = card.name,
                        variants = card.variants.map(CardVariant::name).sorted(),
                    )
                },
                DEFAULT_MAX_AGE_HOURS,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptySet()
        }
        val refreshed = mutableSetOf<String>()
        val observedAt = System.currentTimeMillis()
        for (card in cards) {
            val quote = response.quotes[card.id] ?: continue
            val prices = quote.toCardPrices(card.variants)
            if (prices.isEmpty()) continue
            prices.forEach { cardDao.saveSinglePrice(it.toEntity(card.id), observedAt) }
            refreshed += card.id
        }
        return refreshed
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
