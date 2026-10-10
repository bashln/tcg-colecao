package app.tcg.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface CardDao {
    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun getById(id: String): CardEntity?

    @Query("SELECT * FROM cards WHERE name LIKE '%' || :name || '%'")
    suspend fun searchByName(name: String): List<CardEntity>

    @Query("SELECT * FROM cards WHERE collectorNumber = :collectorNumber AND (:setId IS NULL OR setId = :setId)")
    suspend fun searchByNumber(collectorNumber: String, setId: String?): List<CardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: CardEntity)

    @Query("SELECT * FROM card_prices WHERE cardId = :cardId")
    suspend fun pricesFor(cardId: String): List<CardPriceEntity>

    @Query("DELETE FROM card_prices WHERE cardId = :cardId")
    suspend fun deletePricesFor(cardId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPrices(prices: List<CardPriceEntity>)
}

@Dao
abstract class CollectionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(item: CollectionItemEntity): Long

    @Query("UPDATE collection_items SET quantity = quantity + :quantity WHERE cardId = :cardId AND variant = :variant AND condition = :condition")
    abstract suspend fun addQuantity(cardId: String, variant: CardVariant, condition: Condition, quantity: Int)

    @Query("SELECT * FROM collection_items ORDER BY addedAt DESC, id")
    abstract suspend fun listItems(): List<CollectionItemEntity>

    @Transaction
    @Query("SELECT * FROM collection_items ORDER BY addedAt DESC, id")
    abstract fun observeItemsWithCard(): Flow<List<CollectionItemWithCard>>

    @Query("SELECT * FROM collection_items WHERE cardId = :cardId AND variant = :variant AND condition = :condition")
    abstract suspend fun getByKey(cardId: String, variant: CardVariant, condition: Condition): CollectionItemEntity?

    @Query("DELETE FROM collection_items WHERE id = :itemId")
    abstract suspend fun deleteById(itemId: String)

    @Transaction
    open suspend fun upsertSumming(item: CollectionItemEntity) {
        val rowId = insertIfAbsent(item)
        if (rowId == -1L) addQuantity(item.cardId, item.variant, item.condition, item.quantity)
    }

    @Transaction
    open suspend fun upsertNew(cardId: String, variant: CardVariant, condition: Condition, quantity: Int) {
        upsertSumming(newItem(cardId, variant, condition, quantity))
    }
}

internal fun newItem(
    cardId: String,
    variant: CardVariant,
    condition: Condition,
    quantity: Int,
) = CollectionItemEntity(
    id = UUID.randomUUID().toString(),
    cardId = cardId,
    quantity = quantity,
    condition = condition,
    variant = variant,
    pricePaidAmount = null,
    pricePaidCurrency = null,
    addedAt = System.currentTimeMillis(),
)
