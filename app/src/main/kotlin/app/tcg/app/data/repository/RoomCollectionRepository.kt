package app.tcg.app.data.repository

import app.tcg.app.data.local.CardDao
import app.tcg.app.data.local.CollectionDao
import app.tcg.app.data.local.CurrentHoldingsPriceRow
import app.tcg.app.data.local.newItem
import app.tcg.app.data.local.toCard
import app.tcg.app.data.local.toItem
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.CollectionItemChanges
import app.tcg.core.domain.model.CollectionItemDetails
import app.tcg.core.domain.model.CollectionUpdate
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.CurrentHoldingsHistory
import app.tcg.core.domain.model.HistoryContinuity
import app.tcg.core.domain.model.HoldingsEstimate
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.model.QuoteCoverage
import app.tcg.core.domain.model.RecordedHoldingsEstimate
import app.tcg.core.domain.valuation.Valuation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class RoomCollectionRepository(
    private val collectionDao: CollectionDao,
    private val cardDao: CardDao,
    private val onCollectionChanged: () -> Unit = {},
) : CollectionRepository {

    override suspend fun add(
        card: Card,
        variant: CardVariant,
        condition: Condition,
        quantity: Int,
        pricePaid: Money?,
    ): CollectionItem {
        cardDao.captureCardPrices(card, System.currentTimeMillis())

        val item = collectionDao.upsertSummingAndQueue(
            newItem(card.id, variant, condition, quantity).copy(
                pricePaidAmount = pricePaid?.amount,
                pricePaidCurrency = pricePaid?.currency?.name,
            ),
        )
        onCollectionChanged()
        return item.toItem()
    }

    override suspend fun list(): List<CollectionEntry> {
        val items = collectionDao.listItems()
        return items.mapNotNull { item ->
            val card = cardDao.getById(item.cardId) ?: return@mapNotNull null
            CollectionEntry(item.toItem(), card.toCard(cardDao.pricesFor(item.cardId)))
        }
    }

    override suspend fun getById(itemId: String): CollectionItemDetails? {
        val item = collectionDao.getById(itemId)?.toItem() ?: return null
        val card = cardDao.getById(item.cardId)?.toCard(cardDao.pricesFor(item.cardId))
        return CollectionItemDetails(item, card)
    }

    override suspend fun update(original: CollectionItem, changes: CollectionItemChanges): CollectionUpdate =
        when (val result = collectionDao.updateExact(original, changes)) {
            is app.tcg.app.data.local.CollectionWriteResult.Updated -> {
                onCollectionChanged()
                CollectionUpdate.Updated(result.item)
            }
            app.tcg.app.data.local.CollectionWriteResult.NotFound -> CollectionUpdate.NotFound
            app.tcg.app.data.local.CollectionWriteResult.ChangedSinceLoaded -> CollectionUpdate.ChangedSinceLoaded
            app.tcg.app.data.local.CollectionWriteResult.DuplicateConfiguration -> CollectionUpdate.DuplicateConfiguration
        }

    override fun observe(): Flow<List<CollectionEntry>> =
        collectionDao.observeItemsWithCard().map { rows ->
            rows.map { row ->
                CollectionEntry(
                    row.item.toItem(),
                    row.card.toCard(cardDao.pricesFor(row.card.id)),
                )
            }
        }

    override fun observeCurrentHoldingsHistory(
        source: PriceSource,
        asOfUtcDay: LocalDate,
    ): Flow<CurrentHoldingsHistory> =
        collectionDao.observeCurrentHoldingsPriceRows(source, Valuation.currencyOf(source), asOfUtcDay)
            .map { rows -> projectCurrentHoldingsHistory(rows, source, asOfUtcDay) }

    override suspend fun remove(itemId: String) {
        collectionDao.removeAndQueue(itemId)
        onCollectionChanged()
    }
}

private data class HoldingKey(val cardId: String, val variant: CardVariant)

private data class CapturedQuote(val day: LocalDate, val amount: Double)

internal fun projectCurrentHoldingsHistory(
    rows: List<CurrentHoldingsPriceRow>,
    source: PriceSource,
    asOfUtcDay: LocalDate,
): CurrentHoldingsHistory {
    val holdings = rows.associate { HoldingKey(it.cardId, it.variant) to it.units }
    val events = rows.mapNotNull { row ->
        val day = row.utcDay ?: return@mapNotNull null
        val amount = row.amount ?: return@mapNotNull null
        if (day > asOfUtcDay) return@mapNotNull null
        day to (HoldingKey(row.cardId, row.variant) to CapturedQuote(day, amount))
    }.groupBy({ it.first }, { it.second })
    val latestQuotes = mutableMapOf<HoldingKey, CapturedQuote>()
    val points = mutableListOf<RecordedHoldingsEstimate>()
    var previousCoverage: Set<HoldingKey>? = null

    fun estimate(day: LocalDate): HoldingsEstimate {
        val priced = holdings.filterKeys { it in latestQuotes }
        return HoldingsEstimate(
            value = Money(
                priced.entries.sumOf { (key, units) -> latestQuotes.getValue(key).amount * units },
                Valuation.currencyOf(source),
            ),
            coverage = QuoteCoverage(
                pricedUnits = priced.values.sum(),
                missingUnits = holdings.values.sum() - priced.values.sum(),
                olderThanSevenDaysUnits = priced.entries.sumOf { (key, units) ->
                    if (ChronoUnit.DAYS.between(latestQuotes.getValue(key).day, day) > 7) units else 0
                },
            ),
        )
    }

    events.toSortedMap().forEach { (day, quotes) ->
        quotes.forEach { (key, quote) -> latestQuotes[key] = quote }
        val coverage = latestQuotes.keys.toSet()
        points += RecordedHoldingsEstimate(
            utcDay = day,
            estimate = estimate(day),
            continuity = if (coverage == previousCoverage) {
                HistoryContinuity.CONTINUES_SEGMENT
            } else {
                HistoryContinuity.STARTS_SEGMENT
            },
        )
        previousCoverage = coverage
    }

    return CurrentHoldingsHistory(
        source = source,
        asOfUtcDay = asOfUtcDay,
        totalUnits = holdings.values.sum(),
        recorded = points,
        latest = if (latestQuotes.isEmpty()) null else estimate(asOfUtcDay),
    )
}
