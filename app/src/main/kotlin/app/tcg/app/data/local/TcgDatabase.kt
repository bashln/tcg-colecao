package app.tcg.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [CardEntity::class, CollectionItemEntity::class, CardPriceEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class TcgDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao
    abstract fun collectionDao(): CollectionDao
}
