package app.tcg.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CardEntity::class, CollectionItemEntity::class, CardPriceEntity::class, CardPriceObservationEntity::class, SyncBindingEntity::class, SyncOutboxEntity::class, SyncCursorEntity::class],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class TcgDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao
    abstract fun collectionDao(): CollectionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN setTotal INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `card_price_observations` (
                        `cardId` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `variant` TEXT NOT NULL,
                        `currency` TEXT NOT NULL,
                        `amount` REAL NOT NULL,
                        `observedAt` INTEGER NOT NULL,
                        `utcDay` TEXT NOT NULL,
                        PRIMARY KEY(`cardId`, `source`, `variant`, `currency`, `utcDay`)
                    )""".trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `collection_items` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `sync_binding` (
                        `slot` INTEGER NOT NULL,
                        `identity` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        PRIMARY KEY(`slot`)
                    )""".trimIndent(),
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `sync_outbox` (
                        `sequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `opId` TEXT NOT NULL,
                        `identity` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `itemId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `baseRevision` INTEGER,
                        `revision` INTEGER NOT NULL,
                        `cardId` TEXT,
                        `quantity` INTEGER,
                        `condition` TEXT,
                        `variant` TEXT,
                        `pricePaidAmount` REAL,
                        `pricePaidCurrency` TEXT,
                        `addedAt` INTEGER
                    )""".trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_opId` ON `sync_outbox` (`opId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_identity_sequence` ON `sync_outbox` (`identity`, `sequence`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `sync_cursor` (
                        `identity` TEXT NOT NULL,
                        `lastSequence` INTEGER NOT NULL,
                        PRIMARY KEY(`identity`)
                    )""".trimIndent(),
                )
            }
        }
    }
}
