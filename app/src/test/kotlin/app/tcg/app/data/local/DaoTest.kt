package app.tcg.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import app.tcg.app.testutil.testCard
import app.tcg.app.testutil.testPrice
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.PriceSource
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private lateinit var db: TcgDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TcgDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun second_scan_of_same_card_and_variant_sums_quantity() = runTest {
        val dao = db.collectionDao()
        dao.upsertNew("swsh3-136", CardVariant.NORMAL, Condition.NEAR_MINT, 1)
        dao.upsertNew("swsh3-136", CardVariant.NORMAL, Condition.NEAR_MINT, 1)
        val rows = dao.listItems()
        assertEquals(1, rows.size)
        assertEquals(2, rows.first().quantity)
    }

    @Test
    fun price_observations_replace_within_utc_day_and_keep_the_capture_instant() = runTest {
        val dao = db.cardDao()
        val cardId = "swsh3-136"
        val firstAt = Instant.parse("2026-10-09T23:59:00Z").toEpochMilli()
        val laterSameDay = Instant.parse("2026-10-09T23:59:59Z").toEpochMilli()
        val nextDay = Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()

        dao.captureCardPrices(testCard(cardId, prices = listOf(testPrice(0.27))), firstAt)
        dao.captureCardPrices(testCard(cardId, prices = listOf(testPrice(0.31))), laterSameDay)

        val sameDay = dao.priceObservationsFor(
            cardId,
            PriceSource.TCGPLAYER,
            CardVariant.NORMAL,
            Currency.USD,
        )
        assertEquals(1, sameDay.size)
        assertEquals(0.31, sameDay.single().amount, 0.0)
        assertEquals(laterSameDay, sameDay.single().observedAt)
        assertEquals(LocalDate.parse("2026-10-09"), sameDay.single().utcDay)

        dao.captureCardPrices(testCard(cardId, prices = listOf(testPrice(0.32))), nextDay)

        val twoDays = dao.priceObservationsFor(
            cardId,
            PriceSource.TCGPLAYER,
            CardVariant.NORMAL,
            Currency.USD,
        )
        assertEquals(2, twoDays.size)
        assertEquals(LocalDate.parse("2026-10-10"), twoDays.last().utcDay)
    }

    @Test
    fun null_market_prices_do_not_create_observations() = runTest {
        val dao = db.cardDao()
        val cardId = "swsh3-136"
        val card = testCard(cardId, prices = listOf(testPrice(0.27).copy(market = null)))

        dao.captureCardPrices(card, Instant.parse("2026-10-09T23:59:00Z").toEpochMilli())

        assertTrue(
            dao.priceObservationsFor(
                cardId,
                PriceSource.TCGPLAYER,
                CardVariant.NORMAL,
                Currency.USD,
            ).isEmpty(),
        )
    }

    @Test
    fun migration_2_3_creates_daily_observation_table() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val database = helper.writableDatabase
            TcgDatabase.MIGRATION_2_3.migrate(database)

            database.query("PRAGMA table_info(`card_price_observations`)").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                val primaryKeyColumn = cursor.getColumnIndexOrThrow("pk")
                val primaryKeyColumns = buildList {
                    while (cursor.moveToNext()) {
                        if (cursor.getInt(primaryKeyColumn) > 0) {
                            add(cursor.getInt(primaryKeyColumn) to cursor.getString(nameColumn))
                        }
                    }
                }.sortedBy { it.first }.map { it.second }

                assertEquals(
                    listOf("cardId", "source", "variant", "currency", "utcDay"),
                    primaryKeyColumns,
                )
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun migration_3_4_preserves_populated_collection_and_price_observations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE cards (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
                            db.execSQL("CREATE TABLE collection_items (id TEXT NOT NULL PRIMARY KEY, cardId TEXT NOT NULL, quantity INTEGER NOT NULL, condition TEXT NOT NULL, variant TEXT NOT NULL, pricePaidAmount REAL, pricePaidCurrency TEXT, addedAt INTEGER NOT NULL)")
                            db.execSQL("CREATE TABLE card_price_observations (cardId TEXT NOT NULL, source TEXT NOT NULL, variant TEXT NOT NULL, currency TEXT NOT NULL, amount REAL NOT NULL, observedAt INTEGER NOT NULL, utcDay TEXT NOT NULL, PRIMARY KEY(cardId, source, variant, currency, utcDay))")
                            db.execSQL("INSERT INTO cards (id, name) VALUES ('swsh3-136', 'Furret')")
                            db.execSQL("INSERT INTO collection_items (id, cardId, quantity, condition, variant, pricePaidAmount, pricePaidCurrency, addedAt) VALUES ('item-1', 'swsh3-136', 3, 'NEAR_MINT', 'NORMAL', 2.5, 'USD', 42)")
                            db.execSQL("INSERT INTO card_price_observations (cardId, source, variant, currency, amount, observedAt, utcDay) VALUES ('swsh3-136', 'TCGPLAYER', 'NORMAL', 'USD', 0.27, 42, '2026-10-10')")
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val database = helper.writableDatabase
            TcgDatabase.MIGRATION_3_4.migrate(database)

            database.query("SELECT revision FROM collection_items WHERE id = 'item-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }
            database.query("SELECT quantity, pricePaidAmount FROM collection_items WHERE id = 'item-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0))
                assertEquals(2.5, cursor.getDouble(1), 0.0)
            }
            database.query("SELECT COUNT(*) FROM cards").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM card_price_observations").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM sync_outbox").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            helper.close()
        }
    }
}
