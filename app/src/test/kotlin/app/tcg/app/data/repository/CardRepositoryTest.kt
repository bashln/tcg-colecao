package app.tcg.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tcg.app.data.local.TcgDatabase
import app.tcg.core.data.tcgdex.TcgdexCardSource
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.PriceSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CardRepositoryTest {
    private lateinit var db: TcgDatabase
    private lateinit var repo: RoomCardRepository
    private lateinit var source: FakeTcgdexCardSource

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TcgDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        source = FakeTcgdexCardSource()
        repo = RoomCardRepository(db.cardDao(), source)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun get_card_misses_cache_hits_network_then_serves_second_call_from_cache() = runTest {
        val first = repo.getCard("swsh3-136")
        val second = repo.getCard("swsh3-136")

        assertEquals("Furret", first!!.name)
        assertEquals("Furret", second!!.name)
        assertEquals(1, source.getCalls)
        assertEquals(0.27, second.prices.single().market!!, 0.0)
    }

    @Test
    fun get_card_returns_null_when_local_and_network_miss() = runTest {
        source.getResult = null
        assertNull(repo.getCard("missing"))
    }

    @Test
    fun search_by_name_records_network_once_and_then_reads_cache() = runTest {
        val first = repo.searchByName("Furret")
        val second = repo.searchByName("Furret")

        assertEquals(listOf("swsh3-136"), first.map { it.id })
        assertEquals(listOf("swsh3-136"), second.map { it.id })
        assertEquals(1, source.searchNameCalls)
    }

    @Test
    fun search_by_number_caches_result_for_the_same_query() = runTest {
        repo.searchByNumber("swsh3", "136")
        repo.searchByNumber("swsh3", "136")

        assertEquals(1, source.searchNumberCalls)
    }

    private class FakeTcgdexCardSource : TcgdexCardSource {
        var getCalls = 0
        var searchNameCalls = 0
        var searchNumberCalls = 0
        var getResult: Card? = sampleCard()

        override suspend fun searchByName(name: String, language: String): List<Card> {
            searchNameCalls++
            return listOf(sampleCard())
        }

        override suspend fun searchByNumber(setId: String?, collectorNumber: String, language: String): List<Card> {
            searchNumberCalls++
            return listOf(sampleCard())
        }

        override suspend fun getCard(id: String, language: String): Card? {
            getCalls++
            return getResult
        }
    }

    private companion object {
        fun sampleCard() = Card(
            id = "swsh3-136",
            name = "Furret",
            setId = "swsh3",
            setName = "Escuridão Incandescente",
            collectorNumber = "136",
            rarity = "Incomum",
            hp = 110,
            types = listOf("Incolor"),
            attacks = emptyList(),
            imageUrl = "https://assets.tcgdex.net/pt/swsh/swsh3/136/high.webp",
            variants = setOf(CardVariant.NORMAL),
            prices = listOf(
                CardPrice(
                    source = PriceSource.TCGPLAYER,
                    variant = CardVariant.NORMAL,
                    currency = Currency.USD,
                    market = 0.27,
                    low = null, mid = null, high = null, trend = null,
                    avg1 = null, avg7 = null, avg30 = null, updatedAt = null,
                ),
            ),
        )
    }
}
