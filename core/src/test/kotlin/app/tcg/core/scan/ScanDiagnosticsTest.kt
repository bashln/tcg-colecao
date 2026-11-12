package app.tcg.core.scan

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.OcrLine
import app.tcg.core.domain.model.ParsedCardText
import app.tcg.core.domain.model.Scored
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanDiagnosticsTest {
    private fun card(id: String, name: String, number: String, total: Int?, hp: Int?) =
        Card(id, name, "set", "Set", number, "Rara", hp, emptyList(), emptyList(), null, emptySet(), emptyList(), total)

    @Test fun carries_what_the_scan_read_and_offered() {
        val parsed = ParsedCardText("Pikachu", "027", null, 80, "raw", setTotal = 128)
        val lines = listOf(OcrLine("Pikachu", 175f, 54f), OcrLine("027/128", 1270f, 22f))
        val ranked = listOf(
            Scored(card("30th-027", "Pikachu", "027", 128, 80), 15),
            Scored(card("sv03.5-006", "Charizard ex", "006", 165, 330), 0),
        )

        val diagnostics = ScanDiagnostics.from(parsed, lines, ranked, chosenCardId = "30th-027")
        val json = diagnostics.toJson()

        assertTrue(json.contains("\"parsedName\": \"Pikachu\""), json)
        assertTrue(json.contains("\"collectorNumber\": \"027\""), json)
        assertTrue(json.contains("\"setTotal\": 128"), json)
        assertTrue(json.contains("\"ocrLines\""), json)
        assertTrue(json.contains("\"score\": 15"), json)
        assertTrue(json.contains("\"chosenCardId\": \"30th-027\""), json)
    }

    @Test fun round_trips_through_json() {
        val diagnostics = ScanDiagnostics.from(
            parsed = ParsedCardText(null, null, null, null, ""),
            lines = emptyList(),
            ranked = emptyList(),
        )

        val decoded = kotlinx.serialization.json.Json.decodeFromString(
            ScanDiagnostics.serializer(),
            diagnostics.toJson(),
        )

        assertEquals(diagnostics, decoded)
        assertEquals(null, decoded.chosenCardId)
    }
}
