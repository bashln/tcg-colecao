package app.tcg.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tcg.app.data.local.TcgDatabase
import app.tcg.app.data.local.toEntity
import app.tcg.core.data.tcgdex.TcgdexCardSource
import app.tcg.core.domain.model.Attack
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.model.SetCatalog
import app.tcg.core.data.repository.PriceRefreshReport
import app.tcg.core.data.repository.SetCatalogResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun get_card_fetches_full_card_from_network_each_call() = runTest {
        source.getResult = fullCard()
        val first = repo.getCard("swsh3-136")
        val second = repo.getCard("swsh3-136")

        assertEquals("Furret", first!!.name)
        assertEquals(1, first.attacks.size)
        assertEquals(1, second!!.attacks.size)
        assertEquals(2, source.getCalls)
    }

    @Test
    fun successful_full_card_refresh_records_market_price_history() = runTest {
        source.getResult = fullCard()

        repo.getCard("swsh3-136")

        val observations = db.cardDao().priceObservationsFor(
            "swsh3-136",
            PriceSource.TCGPLAYER,
            CardVariant.NORMAL,
            Currency.USD,
        )
        assertEquals(1, observations.size)
        assertEquals(0.27, observations.single().amount, 0.0)
    }

    @Test
    fun search_results_do_not_record_daily_history() = runTest {
        source.searchResult = fullCard()

        repo.searchByName("Furret")

        assertTrue(
            db.cardDao().priceObservationsFor(
                "swsh3-136",
                PriceSource.TCGPLAYER,
                CardVariant.NORMAL,
                Currency.USD,
            ).isEmpty(),
        )
    }

    @Test
    fun get_card_replaces_cached_brief_with_full_network_card() = runTest {
        db.cardDao().upsert(briefCard().toEntity())
        source.getResult = fullCard()

        val result = repo.getCard("swsh3-136")

        assertEquals(1, result!!.attacks.size)
        assertEquals(1, result.prices.size)
        // the brief already in the cache is upgraded in place, not just returned
        assertEquals("Pancada", db.cardDao().getById("swsh3-136")!!.attacks.single().name)
        assertEquals(1, db.cardDao().pricesFor("swsh3-136").size)
    }

    @Test
    fun get_card_falls_back_to_cached_brief_when_network_fails() = runTest {
        db.cardDao().upsert(briefCard().toEntity())
        source.getError = RuntimeException("offline")

        val result = repo.getCard("swsh3-136")

        assertEquals("Furret", result!!.name)
        assertTrue(result.attacks.isEmpty())
        assertTrue(result.prices.isEmpty())
    }

    @Test
    fun get_card_returns_null_when_local_and_network_miss() = runTest {
        source.getResult = null
        assertNull(repo.getCard("missing"))
    }

    @Test
    fun set_catalog_is_marked_complete_only_when_non_empty_and_is_not_written_to_partial_cache() = runTest {
        val card = sampleCard()
        source.setCatalogResult = SetCatalog("swsh3", listOf(card))

        val result = repo.getSetCatalog("swsh3")

        assertEquals(SetCatalogResult.Complete(SetCatalog("swsh3", listOf(card))), result)
        assertNull(db.cardDao().getById(card.id))

        source.setCatalogError = RuntimeException("offline")
        assertEquals(SetCatalogResult.Unavailable, repo.getSetCatalog("swsh3"))
    }

    @Test
    fun get_card_rethrows_cancellation_instead_of_swallowing_it() = runTest {
        source.getError = CancellationException("cancelled")

        try {
            repo.getCard("swsh3-136")
            fail("CancellationException should propagate so the caller can stop")
        } catch (_: CancellationException) {
            // expected: cancellation is not an offline fallback
        }
    }

    @Test
    fun search_by_name_falls_back_to_cache_when_network_fails() = runTest {
        repo.searchByName("Furret")
        source.searchError = RuntimeException("offline")

        val cached = repo.searchByName("Furret")

        assertEquals(listOf("swsh3-136"), cached.map { it.id })
    }

    @Test
    fun search_by_number_falls_back_to_cache_when_network_fails() = runTest {
        repo.searchByNumber("swsh3", "136")
        source.searchError = RuntimeException("offline")

        val cached = repo.searchByNumber("swsh3", "136")

        assertEquals(listOf("swsh3-136"), cached.map { it.id })
    }

    @Test
    fun search_by_name_keeps_set_total_through_the_cache_when_offline() = runTest {
        source.searchResult = sampleCard().copy(setTotal = 198)
        repo.searchByName("Furret")
        source.searchError = RuntimeException("offline")

        val fromCache = repo.searchByName("Furret").single()

        assertEquals(198, fromCache.setTotal)
    }

    @Test
    fun refresh_prices_records_one_observation_per_card() = runTest {
        source.getResults["swsh3-136"] = pricedCard("swsh3-136", 0.27)
        source.getResults["swsh3-137"] = pricedCard("swsh3-137", 4.10)

        val report = repo.refreshPrices(listOf("swsh3-136", "swsh3-137"))

        assertEquals(PriceRefreshReport(requested = 2, refreshed = 2, unavailable = 0), report)
        assertEquals(1, observations("swsh3-136").size)
        assertEquals(0.27, observations("swsh3-136").single().amount, 0.0)
        assertEquals(1, observations("swsh3-137").size)
        assertEquals(4.10, observations("swsh3-137").single().amount, 0.0)
    }

    @Test
    fun refresh_prices_fetches_a_repeated_id_once() = runTest {
        source.getResults["swsh3-136"] = pricedCard("swsh3-136", 0.27)

        val report = repo.refreshPrices(listOf("swsh3-136", "swsh3-136"))

        assertEquals(1, source.getCalls)
        assertEquals(PriceRefreshReport(requested = 1, refreshed = 1, unavailable = 0), report)
        assertEquals(1, observations("swsh3-136").size)
    }

    @Test
    fun refresh_prices_keeps_going_when_one_card_fails() = runTest {
        source.getResults["swsh3-136"] = pricedCard("swsh3-136", 0.27)
        source.getErrors["swsh3-999"] = RuntimeException("offline")

        val report = repo.refreshPrices(listOf("swsh3-136", "swsh3-999"))

        assertEquals(PriceRefreshReport(requested = 2, refreshed = 1, unavailable = 1), report)
        assertEquals(0.27, observations("swsh3-136").single().amount, 0.0)
        assertTrue(observations("swsh3-999").isEmpty())
    }

    @Test
    fun refresh_prices_rethrows_cancellation() = runTest {
        source.getErrors["swsh3-136"] = CancellationException("cancelled")

        try {
            repo.refreshPrices(listOf("swsh3-136"))
            fail("CancellationException should propagate so the loop stops")
        } catch (_: CancellationException) {
            // expected: cancellation is not an unavailable card
        }
    }

    private suspend fun observations(cardId: String) = db.cardDao().priceObservationsFor(
        cardId,
        PriceSource.TCGPLAYER,
        CardVariant.NORMAL,
        Currency.USD,
    )

    private class FakeTcgdexCardSource : TcgdexCardSource {
        var getCalls = 0
        var searchNameCalls = 0
        var searchNumberCalls = 0
        var getResult: Card? = sampleCard()
        var getError: Exception? = null
        val getResults = mutableMapOf<String, Card>()
        val getErrors = mutableMapOf<String, Exception>()
        var searchResult: Card = sampleCard()
        var searchError: Exception? = null
        var setCatalogResult = SetCatalog("swsh3", listOf(sampleCard()))
        var setCatalogError: Exception? = null

        override suspend fun searchByName(name: String, language: String): List<Card> {
            searchNameCalls++
            searchError?.let { throw it }
            return listOf(searchResult)
        }

        override suspend fun searchByNumber(setId: String?, collectorNumber: String, language: String): List<Card> {
            searchNumberCalls++
            searchError?.let { throw it }
            return listOf(searchResult)
        }

        override suspend fun getCard(id: String, language: String): Card? {
            getCalls++
            getErrors[id]?.let { throw it }
            getError?.let { throw it }
            return getResults[id] ?: getResult
        }

        override suspend fun getSetCatalog(setId: String, language: String): SetCatalog {
            setCatalogError?.let { throw it }
            return setCatalogResult
        }
    }

    private companion object {
        fun briefCard() = sampleCard().copy(attacks = emptyList(), prices = emptyList())

        fun pricedCard(id: String, market: Double) = sampleCard().copy(
            id = id,
            prices = listOf(sampleCard().prices.single().copy(market = market)),
        )

        fun fullCard() = sampleCard().copy(
            attacks = listOf(Attack(name = "Pancada", cost = listOf("Incolor"), damage = "30", text = null)),
        )

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
