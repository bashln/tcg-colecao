package app.tcg.core.data.ligapokemon

import app.tcg.core.domain.model.CardVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LigaQuoteParserTest {

    @Test
    fun meg_175_reads_both_printings() {
        val quote = requireNotNull(LigaQuoteParser.parse(fixture("meg_175.json"), "175"))

        assertEquals(62.02, quote.normalMedium)
        assertEquals(66.48, quote.foilMedium)
        assertEquals(62.02, quote.mediumFor(CardVariant.NORMAL))
        assertEquals(66.48, quote.mediumFor(CardVariant.HOLOFOIL))
    }

    @Test
    fun paf_233_reads_quotes_published_as_strings() {
        val quote = requireNotNull(LigaQuoteParser.parse(fixture("paf_233.json"), "233"))

        assertEquals(580.0, quote.normalMedium)
        assertEquals(406.78, quote.foilMedium)
    }

    @Test
    fun smpj_068_has_no_foil_quote_when_the_foil_bucket_is_an_empty_array() {
        val quote = requireNotNull(LigaQuoteParser.parse(fixture("smpj_068.json"), "068"))

        assertEquals(26250.0, quote.normalMedium)
        assertNull(quote.foilMedium)
        assertNull(quote.mediumFor(CardVariant.REVERSE_HOLOFOIL))
        assertEquals(26250.0, quote.mediumFor(CardVariant.PROMO))
    }

    @Test
    fun maps_every_variant_to_its_printing() {
        val quote = requireNotNull(LigaQuoteParser.parse(BOTH_PRINTINGS))

        assertEquals(10.0, quote.mediumFor(CardVariant.NORMAL))
        assertEquals(25.5, quote.mediumFor(CardVariant.HOLOFOIL))
        assertEquals(25.5, quote.mediumFor(CardVariant.REVERSE_HOLOFOIL))
        assertEquals(25.5, quote.mediumFor(CardVariant.FIRST_EDITION))
        assertEquals(25.5, quote.mediumFor(CardVariant.PROMO))
    }

    @Test
    fun first_edition_and_promo_fall_back_to_the_normal_printing() {
        val quote = requireNotNull(LigaQuoteParser.parse(NORMAL_ONLY))

        assertEquals(10.0, quote.mediumFor(CardVariant.FIRST_EDITION))
        assertEquals(10.0, quote.mediumFor(CardVariant.PROMO))
        assertNull(quote.mediumFor(CardVariant.HOLOFOIL))
        assertNull(quote.mediumFor(CardVariant.REVERSE_HOLOFOIL))
    }

    @Test
    fun collector_number_selects_the_matching_entry() {
        assertEquals(12.5, LigaQuoteParser.parse(TWO_ENTRIES, "010")?.normalMedium)
        assertEquals(25.0, LigaQuoteParser.parse(TWO_ENTRIES, "020")?.normalMedium)
    }

    @Test
    fun unknown_collector_number_falls_back_to_the_first_entry() {
        assertEquals(12.5, LigaQuoteParser.parse(TWO_ENTRIES, "999")?.normalMedium)
        assertEquals(12.5, LigaQuoteParser.parse(TWO_ENTRIES)?.normalMedium)
    }

    @Test
    fun malformed_json_returns_null() {
        assertNull(LigaQuoteParser.parse("""{"num": "175", "price": """))
        assertNull(LigaQuoteParser.parse(""))
    }

    @Test
    fun payload_that_is_not_an_array_returns_null() {
        assertNull(LigaQuoteParser.parse("""{"cards_editions": []}"""))
        assertNull(LigaQuoteParser.parse("[]"))
    }

    @Test
    fun entry_without_any_quote_returns_null() {
        val json = """[{"num": "175", "price": {"2": [], "0": {}}}]"""

        assertNull(LigaQuoteParser.parse(json, "175"))
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/ligapokemon/$name")) { "missing fixture $name" }
            .bufferedReader()
            .use { it.readText() }

    private companion object {
        const val BOTH_PRINTINGS = """
            [
              {
                "num": "001",
                "price": {
                  "0": { "m": 10.0 },
                  "2": { "m": 25.5 }
                }
              }
            ]
        """

        const val NORMAL_ONLY = """
            [
              {
                "num": "001",
                "price": {
                  "0": { "m": 10.0 },
                  "2": []
                }
              }
            ]
        """

        const val TWO_ENTRIES = """
            [
              { "num": "010", "price": { "0": { "m": 12.5 } } },
              { "num": "020", "price": { "0": { "m": 25.0 } } }
            ]
        """
    }
}
