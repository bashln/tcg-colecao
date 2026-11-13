package app.tcg.core.scan

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.ParsedCardText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ScanDecisionTest {
    private fun card(id: String, name: String, number: String, hp: Int?, setTotal: Int? = null) = Card(
        id, name, "set", "Set", number, "Rare", hp, emptyList(), emptyList(), null, emptySet(), emptyList(), setTotal)

    private fun decided(parsed: ParsedCardText, vararg cards: Card): ScanDecision =
        ScanDecision.from(CandidateRanker.rank(parsed, cards.toList()))

    // Device case: the OCR read the number 42 from a Finizen.
    @Test fun finizen_42_with_zebstrika_42_keeps_finizen_on_top_and_confirms() {
        val decision = decided(
            ParsedCardText("Finizen", "42", null, null, ""),
            card("bw1-42", "Zebstrika", "42", null),
            card("me05-021", "Finizen", "021", 80, setTotal = 84),
        )

        val confirmed = assertIs<ScanDecision.Confirmed>(decision)
        assertEquals(listOf("me05-021", "bw1-42"), confirmed.candidates.map { it.card.id })
    }

    @Test fun loose_number_42_across_three_sets_is_too_weak() {
        val decision = decided(
            ParsedCardText(null, "42", null, null, ""),
            card("a-42", "Zebstrika", "42", null),
            card("b-042", "Finizen", "042", null),
            card("c-42", "Pikachu", "42", null),
        )

        assertSame(ScanDecision.TooWeak, decision)
    }

    @Test fun loose_number_42_with_a_single_card_is_confirmed() {
        val decision = decided(
            ParsedCardText(null, "42", null, null, ""),
            card("me05-042", "Finizen", "42", null),
        )

        val confirmed = assertIs<ScanDecision.Confirmed>(decision)
        assertEquals(listOf("me05-042"), confirmed.candidates.map { it.card.id })
    }

    @Test fun partial_name_furr_without_number_is_too_weak() {
        val decision = decided(
            ParsedCardText("Furr", null, null, null, ""),
            card("bw1-136", "Furret", "136", 110),
        )

        assertSame(ScanDecision.TooWeak, decision)
    }

    @Test fun empty_candidate_list_is_too_weak() {
        assertSame(ScanDecision.TooWeak, ScanDecision.from(emptyList()))
    }

    @Test fun exact_name_with_three_homonyms_is_confirmed_with_all_three() {
        val decision = decided(
            ParsedCardText("Pikachu", null, null, null, ""),
            card("base-025", "Pikachu", "025", 40),
            card("xy-035", "Pikachu", "035", 60),
            card("sv-049", "Pikachu", "049", 70),
        )

        val confirmed = assertIs<ScanDecision.Confirmed>(decision)
        assertEquals(3, confirmed.candidates.size)
        assertEquals(
            setOf("base-025", "xy-035", "sv-049"),
            confirmed.candidates.map { it.card.id }.toSet(),
        )
    }

    @Test fun exact_name_outranks_another_cards_number_and_confirms() {
        val decision = decided(
            ParsedCardText("Furret", "136", null, null, ""),
            card("sentret-136", "Sentret", "136", 60),
            card("furret-999", "Furret", "999", 110),
        )

        val confirmed = assertIs<ScanDecision.Confirmed>(decision)
        assertEquals("furret-999", confirmed.candidates.first().card.id)
    }

    @Test fun a_unique_exact_name_moves_ahead_of_set_total_and_hp() {
        val decision = decided(
            ParsedCardText("Furret", null, null, null, "", setTotal = 189),
            card("other-001", "Sentret", "001", null, setTotal = 189),
            card("furret-136", "Furret", "136", 110),
        )

        val confirmed = assertIs<ScanDecision.Confirmed>(decision)
        assertEquals("furret-136", confirmed.candidates.first().card.id)
    }

    @Test fun exact_name_alone_outranks_a_number_and_hp_pair() {
        val decision = decided(
            ParsedCardText("Furret", "136", null, 110, ""),
            card("zebstrika-136", "Zebstrika", "136", 110),
            card("furret-999", "Furret", "999", 90),
        )

        val confirmed = assertIs<ScanDecision.Confirmed>(decision)
        assertEquals("furret-999", confirmed.candidates.first().card.id)
    }
}
