package app.tcg.core.scan

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrintedCardNumberTest {
    @Test
    fun parses_multiple_printed_references_and_keeps_leading_zeroes() {
        assertEquals(
            listOf(PrintedCardNumber("021", 84), PrintedCardNumber("9", 108)),
            parsePrintedCardNumbers("021/084, 9/108\n021/084"),
        )
    }

    @Test
    fun returns_null_when_input_mixes_numbers_with_other_search_text() {
        assertNull(parsePrintedCardNumbers("021/084, Pikachu"))
    }

    @Test
    fun matches_printed_id_and_official_total_without_collapsing_cross_set_ambiguity() {
        val one = card("set-a-21", "021", 84)
        val two = card("set-b-021", "21", 84)
        val wrongTotal = card("set-c-21", "21", 85)

        val result = matchPrintedCardNumbers(
            listOf(PrintedCardNumber("021", 84), PrintedCardNumber("022", 84)),
            listOf(one, two, wrongTotal),
        )

        assertEquals(listOf(one.id, two.id), result.cards.map(Card::id))
        assertEquals(listOf("022/084"), result.unmatchedReferences)
    }

    private fun card(id: String, number: String, total: Int) = Card(
        id = id,
        name = id,
        setId = id.substringBefore('-'),
        setName = id,
        collectorNumber = number,
        rarity = null,
        hp = null,
        types = emptyList(),
        attacks = emptyList(),
        imageUrl = null,
        variants = setOf(CardVariant.NORMAL),
        prices = emptyList(),
        setTotal = total,
    )
}
