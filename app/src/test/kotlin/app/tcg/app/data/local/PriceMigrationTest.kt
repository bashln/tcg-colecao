package app.tcg.app.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PriceMigrationTest {
    @Test
    fun migration_10_11_keeps_prices_and_adds_nullable_provenance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE card_prices (
                                cardId TEXT NOT NULL, source TEXT NOT NULL, variant TEXT NOT NULL,
                                currency TEXT NOT NULL, market REAL, low REAL, mid REAL, high REAL,
                                trend REAL, avg1 REAL, avg7 REAL, avg30 REAL, updatedAt TEXT,
                                PRIMARY KEY(cardId, source, variant))""".trimIndent(),
                        )
                        db.execSQL(
                            """INSERT INTO card_prices
                                (cardId, source, variant, currency, market, mid)
                                VALUES ('sv08.5-001', 'LIGAPOKEMON', 'NORMAL', 'BRL', 5.0, 5.0)""".trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            TcgDatabase.MIGRATION_10_11.migrate(db)
            db.query("SELECT cardId, condition, language, market FROM card_prices").use {
                check(it.moveToFirst())
                assertEquals("sv08.5-001", it.getString(0))
                assertTrue(it.isNull(1))
                assertTrue(it.isNull(2))
                assertEquals(5.0, it.getDouble(3), 0.001)
            }
        } finally {
            helper.close()
        }
    }
}
