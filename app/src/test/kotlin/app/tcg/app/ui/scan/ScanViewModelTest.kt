package app.tcg.app.ui.scan

import app.tcg.core.data.repository.CardRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.OcrLine
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanViewModelTest {

    @Test
    fun recognized_text_produces_ranked_candidates() = runTest {
        val repo = FakeCardRepository(listOf(card("b", "Furret", "136", 110)))
        val vm = ScanViewModel(repo)
        vm.onTextRecognized(listOf(OcrLine("Furret", 0f, 40f), OcrLine("136/189", 100f, 40f)))
        assertEquals("b", vm.state.value.candidates.first().value.id)
    }

    @Test
    fun no_candidate_sets_manual_search_flag() = runTest {
        val vm = ScanViewModel(FakeCardRepository(emptyList()))
        vm.onTextRecognized(listOf(OcrLine("Zzz", 0f, 40f)))
        assertTrue(vm.state.value.manualSearch)
    }

    @Test
    fun network_failure_sets_error_and_manual_search_without_crashing() = runTest {
        val vm = ScanViewModel(FakeCardRepository(failing = true))
        vm.onTextRecognized(listOf(OcrLine("Furret", 0f, 40f), OcrLine("136/189", 100f, 40f)))
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.manualSearch)
        assertTrue(vm.state.value.candidates.isEmpty())
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun manual_search_by_name_produces_candidates() = runTest {
        val repo = FakeCardRepository(listOf(card("b", "Furret", "136", 110)))
        val vm = ScanViewModel(repo)
        vm.onManualSearch("Furret")
        assertEquals("b", vm.state.value.candidates.first().value.id)
        assertFalse(vm.state.value.manualSearch)
    }

    private class FakeCardRepository(
        private val result: List<Card> = emptyList(),
        private val failing: Boolean = false,
    ) : CardRepository {
        override suspend fun searchByName(name: String): List<Card> {
            if (failing) throw IOException("offline")
            return result
        }

        override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> {
            if (failing) throw IOException("offline")
            return result
        }

        override suspend fun getCard(id: String): Card? = result.firstOrNull { it.id == id }
    }

    private companion object {
        fun card(id: String, name: String, number: String, hp: Int?) = Card(
            id = id,
            name = name,
            setId = "set",
            setName = "Set",
            collectorNumber = number,
            rarity = "Rare",
            hp = hp,
            types = emptyList(),
            attacks = emptyList(),
            imageUrl = null,
            variants = emptySet(),
            prices = emptyList(),
        )
    }
}
