package app.tcg.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
