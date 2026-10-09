package app.tcg.core.scan

import app.tcg.core.domain.model.OcrLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OcrParserTest {
    private fun lines(vararg t: String) = t.mapIndexed { i, s -> OcrLine(s, i * 100f, 40f) }

    @Test fun extracts_collector_number_with_slash() {
        val r = OcrParser.parse(lines("Furret", "HP 110", "136/189"))
        assertEquals("136", r.collectorNumber)
    }

    @Test fun trims_leading_zeros_from_number() {
        assertEquals("006", OcrParser.parse(lines("Charizard ex", "006/131")).collectorNumber)
        assertEquals("6", OcrParser.parse(lines("Charizard", "06/131")).collectorNumber)
    }

    @Test fun extracts_promo_code_without_slash() {
        val r = OcrParser.parse(lines("Noctowl", "SWSH027"))
        assertNull(r.collectorNumber)
        assertEquals("SWSH027", r.promoCode)
    }

    @Test fun picks_topmost_large_text_as_name_and_drops_noise() {
        val r = OcrParser.parse(lines("BASIC", "Furret", "HP 110", "Pancada de Cauda"))
        assertEquals("Furret", r.name)
    }

    @Test fun extracts_hp() {
        assertEquals(110, OcrParser.parse(lines("Furret", "HP 110")).hp)
    }

    @Test fun returns_nulls_on_empty_input() {
        val r = OcrParser.parse(emptyList())
        assertNull(r.name); assertNull(r.collectorNumber); assertNull(r.promoCode); assertNull(r.hp)
    }

    @Test fun keeps_raw_text() {
        assertTrue(OcrParser.parse(lines("Furret", "136/189")).rawText.contains("136/189"))
    }

    @Test fun keeps_accented_name_intact() {
        assertEquals("Escuridão", OcrParser.parse(lines("Escuridão", "136/189")).name)
    }
}
