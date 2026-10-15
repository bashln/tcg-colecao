package app.tcg.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tcg.app.data.local.TcgDatabase
import app.tcg.app.data.local.toPrice
import app.tcg.app.testutil.testCard
import app.tcg.app.testutil.testPrice
import app.tcg.core.domain.model.CardListKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomCardListRepositoryTest {
    private lateinit var database: TcgDatabase
    private lateinit var repository: RoomCardListRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TcgDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomCardListRepository(database, database.cardListDao(), database.cardDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun assignment_deduplicates_cards_and_retries_without_duplicate_memberships() = runTest {
        val wanted = repository.create("  Quero  ", CardListKind.WANTED)
        val missing = repository.create("Faltantes", CardListKind.MISSING)
        val furret = testCard("swsh3-136", prices = listOf(testPrice(0.27)))
        val charizard = testCard("swsh4-25")
        database.cardDao().captureCardPrices(furret, System.currentTimeMillis())

        val first = repository.addCards(
            listOf(furret.copy(prices = emptyList()), charizard, furret.copy(prices = emptyList())),
            setOf(wanted.id, missing.id),
        )
        val retry = repository.addCards(listOf(furret.copy(prices = emptyList()), charizard), setOf(wanted.id, missing.id))

        assertEquals(4, first.addedMemberships)
        assertEquals(0, first.existingMemberships)
        assertEquals(0, retry.addedMemberships)
        assertEquals(4, retry.existingMemberships)
        assertEquals("Quero", repository.observeLists().first().first { it.list.id == wanted.id }.list.name)
        assertEquals(2, repository.observeLists().first().first { it.list.id == wanted.id }.cardCount)
        assertEquals(listOf(furret.id, charizard.id), repository.observeList(wanted.id).first()?.cards?.map { it.id })
        assertEquals(listOf(testPrice(0.27)), database.cardDao().pricesFor(furret.id).map { it.toPrice() })
    }

    @Test
    fun assignment_to_missing_destination_is_atomic() = runTest {
        val existing = repository.create("Quero", CardListKind.WANTED)

        val failure = runCatching {
            repository.addCards(listOf(testCard("swsh3-136")), setOf(existing.id, "deleted-list"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertNull(database.cardDao().getById("swsh3-136"))
        assertTrue(repository.observeList(existing.id).first()?.cards.orEmpty().isEmpty())
    }

    @Test
    fun deleting_list_removes_members_but_keeps_card_cache() = runTest {
        val list = repository.create("Quero", CardListKind.WANTED)
        repository.addCards(listOf(testCard("swsh3-136")), setOf(list.id))

        repository.delete(list.id)

        assertFalse(repository.observeLists().first().any { it.list.id == list.id })
        assertTrue(database.cardListDao().observeCards(list.id).first().isEmpty())
        assertTrue(database.cardDao().getById("swsh3-136") != null)
    }
}
