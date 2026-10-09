package app.tcg.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tcg.app.data.local.TcgDatabase
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.valuation.Valuation
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CollectionRepositoryTest {
    private lateinit var db: TcgDatabase
    private lateinit var repo: RoomCollectionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TcgDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomCollectionRepository(db.collectionDao(), db.cardDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun add_persists_item_with_price_and_resolves_card_in_list() = runTest {
        val item = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, Money(2.0, Currency.USD))

        assertEquals("swsh3-136", item.cardId)
        assertEquals(1, item.quantity)
        assertEquals(2.0, item.pricePaid!!.amount, 0.0)
        assertEquals(Currency.USD, item.pricePaid!!.currency)

        val entries = repo.list()
        assertEquals(1, entries.size)
        assertEquals(item.id, entries.first().item.id)
        assertEquals("Furret", entries.first().card.name)
        assertEquals("swsh3", entries.first().card.setId)
    }

    @Test
    fun second_add_of_same_card_and_variant_sums_quantity_and_keeps_first_price() = runTest {
        repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, Money(2.0, Currency.USD))
        val second = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 2, Money(9.0, Currency.USD))

        assertEquals(3, second.quantity)
        assertEquals(2.0, second.pricePaid!!.amount, 0.0)
        assertEquals(1, repo.list().size)
    }

    @Test
    fun list_resolves_prices_so_valuation_works_offline() = runTest {
        repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, null)

        val entry = repo.list().single()
        assertEquals(0.27, Valuation.itemValue(entry.card, CardVariant.NORMAL, PriceSource.TCGPLAYER)!!.amount, 0.0)
        assertEquals(0.07, Valuation.itemValue(entry.card, CardVariant.NORMAL, PriceSource.CARDMARKET)!!.amount, 0.0)
    }

    @Test
    fun add_without_price_persists_null_price_paid() = runTest {
        val item = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, null)
        assertNull(item.pricePaid)
        assertNull(repo.list().single().item.pricePaid)
    }

    @Test
    fun remove_deletes_the_item() = runTest {
        val item = repo.add(sampleCard(), CardVariant.NORMAL, Condition.NEAR_MINT, 1, null)

        repo.remove(item.id)

        assertTrue(repo.list().isEmpty())
    }

    private fun sampleCard() = Card(
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
        variants = setOf(CardVariant.NORMAL, CardVariant.REVERSE_HOLOFOIL),
        prices = listOf(
            price(PriceSource.TCGPLAYER, Currency.USD, 0.27),
            price(PriceSource.CARDMARKET, Currency.EUR, 0.07),
        ),
    )

    private fun price(source: PriceSource, currency: Currency, market: Double) = CardPrice(
        source = source,
        variant = CardVariant.NORMAL,
        currency = currency,
        market = market,
        low = null, mid = null, high = null, trend = null,
        avg1 = null, avg7 = null, avg30 = null, updatedAt = null,
    )
}
