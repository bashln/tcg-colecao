package app.tcg.core.scan

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.ParsedCardText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CandidateRankerTest {
    private fun card(id: String, name: String, number: String, hp: Int?) = Card(
        id, name, "set", "Set", number, "Rare", hp, emptyList(), emptyList(), null, emptySet(), emptyList())

    @Test fun ranks_exact_number_and_name_match_first() {
        val parsed = ParsedCardText("Furret", "136", null, 110, "")
        val result = CandidateRanker.rank(parsed, listOf(
            card("a", "Sentret", "136", 60), card("b", "Furret", "136", 110)))
        assertEquals("b", result.first().value.id)
    }

    @Test fun hp_breaks_tie_on_same_name_and_number() {
        val parsed = ParsedCardText("Charizard", "6", null, 170, "")
        val result = CandidateRanker.rank(parsed, listOf(
            card("x", "Charizard", "6", 120), card("y", "Charizard", "6", 170)))
        assertEquals("y", result.first().value.id)
    }

    @Test fun name_similarity_still_scores_when_number_missing() {
        val parsed = ParsedCardText("Furret", null, null, null, "")
        val result = CandidateRanker.rank(parsed, listOf(card("a", "Furret", "1", 110)))
        assertTrue(result.first().score > 0)
    }

    @Test fun zero_padded_parsed_number_exactly_matches_unpadded_card_number() {
        val parsed = ParsedCardText("Charmander", "001", null, null, "")
        val result = CandidateRanker.rank(parsed, listOf(card("a", "Squirtle", "1", null)))
        assertEquals(4, result.first().score)
    }

    @Test fun unpadded_parsed_number_exactly_matches_zero_padded_card_number() {
        val parsed = ParsedCardText("Charmander", "1", null, null, "")
        val result = CandidateRanker.rank(parsed, listOf(card("a", "Squirtle", "001", null)))
        assertEquals(4, result.first().score)
    }

    @Test fun padded_parsed_number_ranks_unpadded_card_over_same_number_other_set() {
        val parsed = ParsedCardText("Bulbasaur", "001", null, 70, "")
        val result = CandidateRanker.rank(parsed, listOf(
            card("other", "Charmander", "001", 70),
            card("target", "Bulbasaur", "1", 70)))
        assertEquals("target", result.first().value.id)
    }
}
