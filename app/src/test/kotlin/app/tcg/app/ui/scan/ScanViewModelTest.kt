package app.tcg.app.ui.scan

import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.SetCatalogResult
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.OcrLine
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanViewModelTest {

    @Test
    fun preparing_new_capture_clears_previous_results_and_errors() {
        val vm = ScanViewModel(FakeCardRepository(listOf(card("b", "Furret", "136", 110))))
        vm.onCaptureError("Erro anterior")

        vm.prepareForCapture()

        assertTrue(vm.state.value.candidates.isEmpty())
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.manualSearch)
    }

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
    fun empty_ocr_text_sets_manual_search_flag() = runTest {
        val vm = ScanViewModel(FakeCardRepository(listOf(card("b", "Furret", "136", 110))))
        vm.onTextRecognized(emptyList())
        assertTrue(vm.state.value.candidates.isEmpty())
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
    fun manual_search_failure_sets_error_and_manual_search() = runTest {
        val vm = ScanViewModel(FakeCardRepository(failing = true))
        vm.onManualSearch("Furret")
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.manualSearch)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun capture_error_sets_error_and_manual_search() = runTest {
        val vm = ScanViewModel(FakeCardRepository())
        vm.onCaptureError("Falha ao capturar. Tente novamente.")
        assertEquals("Falha ao capturar. Tente novamente.", vm.state.value.error)
        assertTrue(vm.state.value.manualSearch)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun cancellation_from_repository_is_rethrown_not_swallowed() = runTest {
        val vm = ScanViewModel(FakeCardRepository(cancellation = true))
        assertFailsWith<CancellationException> {
            vm.onTextRecognized(listOf(OcrLine("Furret", 0f, 40f)))
        }
        assertNull(vm.state.value.error)
    }

    @Test
    fun manual_search_by_name_produces_candidates() = runTest {
        val repo = FakeCardRepository(listOf(card("b", "Furret", "136", 110)))
        val vm = ScanViewModel(repo)
        vm.onManualSearch("Furret")
        assertEquals("b", vm.state.value.candidates.first().value.id)
        assertFalse(vm.state.value.manualSearch)
    }

    @Test
    fun manual_batch_search_keeps_ambiguous_matches_and_reports_unmatched_ids() = runTest {
        val firstSet = card("set-a-21", "Pikachu A", "021", null).copy(setTotal = 84)
        val secondSet = card("set-b-21", "Pikachu B", "21", null).copy(setTotal = 84)
        val wrongSetSize = card("set-c-21", "Pikachu C", "21", null).copy(setTotal = 85)
        val repo = BatchCardRepository(listOf(firstSet, secondSet, wrongSetSize))
        val vm = ScanViewModel(repo)

        vm.onManualSearch("021/084, 022/084")

        assertEquals(setOf("021", "022"), repo.requestedNumbers)
        assertEquals(listOf(firstSet.id, secondSet.id), vm.state.value.candidates.map { it.value.id })
        assertEquals(listOf("022/084"), vm.state.value.unmatchedPrintedIds)
        assertEquals(2, vm.state.value.batchSearchCount)
    }

    @Test
    fun manual_search_replaces_previous_candidates() = runTest {
        val repo = FakeCardRepository(listOf(card("b", "Furret", "136", 110)))
        val vm = ScanViewModel(repo)
        vm.onTextRecognized(listOf(OcrLine("Furret", 0f, 40f), OcrLine("136/189", 100f, 40f)))

        vm.onManualSearch("Furret")

        assertEquals(listOf("b"), vm.state.value.candidates.map { it.value.id })
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun late_search_result_cannot_replace_results_for_new_capture() = runTest {
        val oldSearch = CompletableDeferred<List<Card>>()
        val repo = DelayedCardRepository(oldSearch, listOf(card("new", "Meowth", "161", 40)))
        val vm = ScanViewModel(repo)

        val oldRequest = async {
            vm.onTextRecognized(listOf(OcrLine("Furret", 0f, 40f)))
        }
        runCurrent()
        vm.prepareForCapture()
        vm.onTextRecognized(listOf(OcrLine("Meowth", 0f, 40f)))
        oldSearch.complete(listOf(card("old", "Furret", "136", 110)))
        oldRequest.await()

        assertEquals(listOf("new"), vm.state.value.candidates.map { it.value.id })
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun name_and_number_searches_both_and_combines() = runTest {
        val repo = RecordingCardRepository(listOf(card("z", "Zubat", "049", 40)))
        val vm = ScanViewModel(repo)
        vm.onTextRecognized(listOf(OcrLine("Zubat", 0f, 40f), OcrLine("049/086", 100f, 40f)))
        assertEquals(listOf("name", "number"), repo.calls)
    }

    @Test
    fun name_and_number_merge_dedupes_same_card_and_ranks_it_first() = runTest {
        val target = card("sv03.5-001", "Bulbasaur", "1", 70)
        val other = card("sv04-001", "Charmander", "001", 70)
        val repo = SplitCardRepository(byName = listOf(target), byNumber = listOf(other, target))
        val vm = ScanViewModel(repo)
        vm.onTextRecognized(listOf(OcrLine("Bulbasaur", 0f, 40f), OcrLine("001/198", 100f, 40f)))
        val ids = vm.state.value.candidates.map { it.value.id }
        assertEquals(1, ids.count { it == "sv03.5-001" })
        assertEquals("sv03.5-001", ids.first())
    }

    @Test
    fun merged_name_and_number_candidates_are_capped_at_ten() = runTest {
        val byName = (1..6).map { card("name-$it", "Frogadier", "001", 120) }
        val byNumber = (1..6).map { card("num-$it", "Charmander", "001", 60) }
        val repo = SplitCardRepository(byName = byName, byNumber = byNumber)
        val vm = ScanViewModel(repo)
        vm.onTextRecognized(listOf(OcrLine("Frogadier", 0f, 40f), OcrLine("001/198", 100f, 40f)))
        assertEquals(10, vm.state.value.candidates.size)
    }

    @Test
    fun number_result_survives_when_its_name_does_not_match_the_parsed_name() = runTest {
        val nameHit = card("name-hit", "Frogadier", "999", 120)
        val numberHit = card("num-hit", "Greninja", "001", 120)
        val repo = SplitCardRepository(byName = listOf(nameHit), byNumber = listOf(numberHit))
        val vm = ScanViewModel(repo)
        vm.onTextRecognized(listOf(OcrLine("Frogadier", 0f, 40f), OcrLine("001/198", 100f, 40f)))
        assertTrue(
            "a misread name must not discard the correct number result",
            vm.state.value.candidates.any { it.value.id == "num-hit" },
        )
    }

    @Test
    fun name_and_number_falls_back_to_full_merge_when_nothing_name_matches() = runTest {
        val unrelated = listOf(card("a", "Charmander", "001", 60), card("b", "Bulbasaur", "001", 70))
        val repo = SplitCardRepository(byName = emptyList(), byNumber = unrelated)
        val vm = ScanViewModel(repo)
        vm.onTextRecognized(listOf(OcrLine("Frogadier", 0f, 40f), OcrLine("001/198", 100f, 40f)))
        assertEquals(unrelated.map { it.id }.toSet(), vm.state.value.candidates.map { it.value.id }.toSet())
    }

    @Test
    fun name_only_keeps_candidates() = runTest {
        val repo = SplitCardRepository(byName = listOf(card("z", "Zubat", "049", 40)), byNumber = emptyList())
        val vm = ScanViewModel(repo)
        vm.onManualSearch("Zubat")
        assertEquals(listOf("Zubat"), vm.state.value.candidates.map { it.value.name })
    }

    @Test
    fun number_only_keeps_all_number_results_even_without_name_match() = runTest {
        val results = listOf(card("a", "Charmander", "049", 60), card("b", "Squirtle", "049", 60))
        val repo = SplitCardRepository(byName = emptyList(), byNumber = results)
        val vm = ScanViewModel(repo)
        vm.onTextRecognized(listOf(OcrLine("049/086", 100f, 40f)))
        assertEquals(results.map { it.id }.toSet(), vm.state.value.candidates.map { it.value.id }.toSet())
    }

    @Test
    fun name_only_searches_by_name() = runTest {
        val repo = RecordingCardRepository(listOf(card("z", "Zubat", "049", 40)))
        val vm = ScanViewModel(repo)
        vm.onTextRecognized(listOf(OcrLine("Zubat", 0f, 40f)))
        assertEquals(listOf("name"), repo.calls)
    }

    @Test
    fun number_only_searches_by_number() = runTest {
        val repo = RecordingCardRepository(listOf(card("z", "Zubat", "049", 40)))
        val vm = ScanViewModel(repo)
        vm.onTextRecognized(listOf(OcrLine("049/086", 100f, 40f)))
        assertEquals(listOf("number"), repo.calls)
    }

    private class RecordingCardRepository(
        private val result: List<Card>,
    ) : CardRepository {
        val calls = mutableListOf<String>()

        override suspend fun searchByName(name: String): List<Card> {
            calls += "name"
            return result
        }

        override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> {
            calls += "number"
            return result
        }

        override suspend fun getCard(id: String): Card? = result.firstOrNull { it.id == id }

        override suspend fun getSetCatalog(setId: String): SetCatalogResult = SetCatalogResult.Unavailable
    }

    private class BatchCardRepository(private val result: List<Card>) : CardRepository {
        var requestedNumbers: Set<String> = emptySet()

        override suspend fun searchByName(name: String): List<Card> = emptyList()

        override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> = emptyList()

        override suspend fun searchByNumbers(collectorNumbers: Set<String>): List<Card> {
            requestedNumbers = collectorNumbers
            return result
        }

        override suspend fun getCard(id: String): Card? = result.firstOrNull { it.id == id }

        override suspend fun getSetCatalog(setId: String): SetCatalogResult = SetCatalogResult.Unavailable
    }

    private class SplitCardRepository(
        private val byName: List<Card>,
        private val byNumber: List<Card>,
    ) : CardRepository {
        override suspend fun searchByName(name: String): List<Card> = byName

        override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> = byNumber

        override suspend fun getCard(id: String): Card? =
            (byName + byNumber).firstOrNull { it.id == id }

        override suspend fun getSetCatalog(setId: String): SetCatalogResult = SetCatalogResult.Unavailable
    }

    private class DelayedCardRepository(
        private val firstSearch: CompletableDeferred<List<Card>>,
        private val nextSearch: List<Card>,
    ) : CardRepository {
        private var nameSearchCount = 0

        override suspend fun searchByName(name: String): List<Card> =
            if (nameSearchCount++ == 0) firstSearch.await() else nextSearch

        override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> = emptyList()

        override suspend fun getCard(id: String): Card? = null

        override suspend fun getSetCatalog(setId: String): SetCatalogResult = SetCatalogResult.Unavailable
    }

    private class FakeCardRepository(
        private val result: List<Card> = emptyList(),
        private val failing: Boolean = false,
        private val cancellation: Boolean = false,
    ) : CardRepository {
        override suspend fun searchByName(name: String): List<Card> = respond()

        override suspend fun searchByNumber(setId: String?, collectorNumber: String): List<Card> = respond()

        override suspend fun getCard(id: String): Card? = result.firstOrNull { it.id == id }

        override suspend fun getSetCatalog(setId: String): SetCatalogResult = SetCatalogResult.Unavailable

        private fun respond(): List<Card> = when {
            cancellation -> throw CancellationException("cancelled")
            failing -> throw IOException("offline")
            else -> result
        }
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
