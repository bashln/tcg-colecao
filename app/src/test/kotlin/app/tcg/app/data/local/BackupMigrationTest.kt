package app.tcg.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupMigrationTest {
    @Test
    fun migration_preserves_existing_holdings_and_tracks_all_collection_writes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE collection_items (id TEXT PRIMARY KEY, quantity INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO collection_items VALUES ('existing', 2)")
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            TcgDatabase.MIGRATION_8_9.migrate(db)
            fun revision(): Long = db.query("SELECT pendingRevision FROM backup_state WHERE slot = 0").use {
                check(it.moveToFirst())
                it.getLong(0)
            }
            assertEquals(1L, revision())
            db.execSQL("INSERT INTO collection_items VALUES ('new', 1)")
            assertEquals(2L, revision())
            db.execSQL("UPDATE collection_items SET quantity = 3 WHERE id = 'existing'")
            assertEquals(3L, revision())
            db.execSQL("DELETE FROM collection_items WHERE id = 'new'")
            assertEquals(4L, revision())
        } finally {
            helper.close()
        }
    }

    @Test
    fun fresh_room_database_installs_the_same_triggers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, TcgDatabase::class.java)
            .addCallback(TcgDatabase.BACKUP_TRIGGERS)
            .allowMainThreadQueries()
            .build()
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.query("SELECT pendingRevision, savedRevision FROM backup_state WHERE slot = 0").use {
                check(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
                assertEquals(-1L, it.getLong(1))
            }
            sqlite.execSQL("""INSERT INTO collection_items
                (id, cardId, quantity, condition, variant, pricePaidAmount, pricePaidCurrency, addedAt, revision)
                VALUES ('item-1234', 'card-1', 1, 'NEAR_MINT', 'NORMAL', NULL, NULL, 0, 1)""".trimIndent())
            sqlite.query("SELECT pendingRevision FROM backup_state WHERE slot = 0").use {
                check(it.moveToFirst())
                assertEquals(1L, it.getLong(0))
            }
        } finally {
            db.close()
        }
    }
}
