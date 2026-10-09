package app.tcg.app.data.repository

import app.tcg.app.data.local.CardDao
import app.tcg.app.data.local.CollectionDao
import app.tcg.app.data.local.newItem
import app.tcg.app.data.local.toCard
import app.tcg.app.data.local.toEntity
import app.tcg.app.data.local.toItem
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomCollectionRepository(
    private val collectionDao: CollectionDao,
    private val cardDao: CardDao,
) : CollectionRepository {

    override suspend fun add(
        card: Card,
        variant: CardVariant,
        condition: Condition,
        quantity: Int,
        pricePaid: Money?,
    ): CollectionItem {
        cardDao.upsert(card.toEntity())
        cardDao.deletePricesFor(card.id)
        cardDao.upsertPrices(card.prices.map { it.toEntity(card.id) })

        collectionDao.upsertSumming(
            newItem(card.id, variant, condition, quantity).copy(
                pricePaidAmount = pricePaid?.amount,
                pricePaidCurrency = pricePaid?.currency?.name,
            ),
        )
        return checkNotNull(collectionDao.getByKey(card.id, variant, condition)).toItem()
    }

    override suspend fun list(): List<CollectionEntry> {
        val items = collectionDao.listItems()
        return items.mapNotNull { item ->
            val card = cardDao.getById(item.cardId) ?: return@mapNotNull null
            CollectionEntry(item.toItem(), card.toCard(cardDao.pricesFor(item.cardId)))
        }
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

    override suspend fun remove(itemId: String) {
        collectionDao.deleteById(itemId)
    }
}
