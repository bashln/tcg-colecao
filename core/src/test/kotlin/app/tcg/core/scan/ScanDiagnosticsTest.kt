package app.tcg.core.scan

import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.OcrLine
import app.tcg.core.domain.model.ParsedCardText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanDiagnosticsTest {
    private fun card(id: String, name: String, number: String, total: Int?, hp: Int?) =
        Card(id, name, "set", "Set", number, "Rara", hp, emptyList(), emptyList(), null, emptySet(), emptyList(), total)

    @Test fun carries_what_the_scan_read_and_offered() {
        val parsed = ParsedCardText("Pikachu", "027", null, 80, "raw", setTotal = 128)
        val lines = listOf(OcrLine("Pikachu", 175f, 54f), OcrLine("027/128", 1270f, 22f))
        val ranked = listOf(
            RankedCandidate(card("30th-027", "Pikachu", "027", 128, 80), setOf(ScanSignal.EXACT_NAME)),
            RankedCandidate(card("sv03.5-006", "Charizard ex", "006", 165, 330), emptySet()),
        )

        val diagnostics = ScanDiagnostics.from(parsed, lines, ranked)
        val json = diagnostics.toJson()

        assertTrue(json.contains("\"parsedName\": \"Pikachu\""), json)
        assertTrue(json.contains("\"collectorNumber\": \"027\""), json)
        assertTrue(json.contains("\"setTotal\": 128"), json)
        assertTrue(json.contains("\"ocrLines\""), json)
        assertTrue(json.contains("\"score\": 7"), json)
    }

    @Test fun records_the_decision_and_the_top_signals() {
        val parsed = ParsedCardText("Pikachu", "042", null, 80, "raw")
        val ranked = listOf(
            RankedCandidate(
                card("p-042", "Pikachu", "042", null, 80),
                setOf(ScanSignal.HP, ScanSignal.EXACT_NAME),
            ),
        )

        val json = ScanDiagnostics.from(parsed, emptyList(), ranked).toJson()

        assertTrue(json.contains("\"decision\": \"CONFIRMED\""), json)
        assertTrue(json.contains("\"EXACT_NAME\""), json)
        assertTrue(json.contains("\"HP\""), json)
    }

    @Test fun records_a_too_weak_decision_alongside_the_top_signals() {
        val parsed = ParsedCardText(null, "042", null, null, "")
        val ranked = listOf(
            RankedCandidate(card("a-042", "Finizen", "042", null, null), setOf(ScanSignal.COLLECTOR_NUMBER)),
            RankedCandidate(card("b-042", "Zebstrika", "042", null, null), setOf(ScanSignal.COLLECTOR_NUMBER)),
        )

        val json = ScanDiagnostics.from(parsed, emptyList(), ranked).toJson()

        assertTrue(json.contains("\"decision\": \"TOO_WEAK\""), json)
        assertTrue(json.contains("\"topSignals\":"), json)
        assertTrue(json.contains("\"COLLECTOR_NUMBER\""), json)
    }

    @Test fun carries_the_box_of_every_ocr_line() {
        val lines = listOf(OcrLine("Vulpix", 120f, 44f, left = 96f, width = 210f), OcrLine("019/132", 1300f, 22f))
        val diagnostics = ScanDiagnostics.from(
            ParsedCardText("Vulpix", "019", null, null, "", setTotal = 132),
            lines,
            emptyList(),
        )

        assertEquals(2, diagnostics.ocrBoxes.size)
        assertEquals(OcrBox(left = 96f, top = 120f, width = 210f, height = 44f), diagnostics.ocrBoxes.first())
        assertTrue(diagnostics.toJson().contains("\"ocrBoxes\""), diagnostics.toJson())
    }

    @Test fun decodes_a_diagnostics_written_before_the_ocr_boxes_existed() {
        val old = """
            {
              "parsedName": "Finizen",
              "collectorNumber": "042",
              "setTotal": null,
              "hp": null,
              "promoCode": null,
              "ocrLines": ["042/189"],
              "candidates": []
            }
        """.trimIndent()

        val decoded = kotlinx.serialization.json.Json.decodeFromString(ScanDiagnostics.serializer(), old)

        assertEquals(emptyList(), decoded.ocrBoxes)
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
    }

    @Test fun decodes_a_diagnostics_written_before_the_decision_fields_existed() {
        val old = """
            {
              "parsedName": "Finizen",
              "collectorNumber": "042",
              "setTotal": null,
              "hp": null,
              "promoCode": null,
              "ocrLines": ["042/189"],
              "candidates": []
            }
        """.trimIndent()

        val decoded = kotlinx.serialization.json.Json.decodeFromString(ScanDiagnostics.serializer(), old)

        assertNull(decoded.decision)
        assertEquals(emptyList(), decoded.topSignals)
        assertEquals(ScanOrigin.CAMERA, decoded.origin)
    }
}
