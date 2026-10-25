package app.tcg.app.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The money conversion is shared by every encoder and decoder, so the shared vectors
 * are the source of truth for both platforms.
 */
class SyncMoneyUnitsTest {

    @Test
    fun `rounding cases from the shared vectors produce the expected minor units`() {
        val root = vectors().getValue("roundingCases").jsonArray

        for (element in root) {
            val testCase = element.jsonObject
            val name = testCase.getValue("name").jsonPrimitive.content
            val major = testCase.getValue("major").jsonPrimitive.content.toDouble()
            val minor = testCase.getValue("minor").jsonPrimitive.content.toLong()

            assertEquals(name, minor, toMinorUnits(major))
            assertEquals(name, major, fromMinorUnits(minor), 1e-9)
        }
    }

    @Test
    fun `the shared price vector and the maximum safe price convert to the expected minor units`() {
        val priceVector = vectors().getValue("priceVector").jsonObject
        val major = priceVector.getValue("major").jsonPrimitive.content.toDouble()
        val minor = priceVector.getValue("minor").jsonPrimitive.content.toLong()

        assertEquals(minor, toMinorUnits(major))
        assertEquals(9007199254740991L, toMinorUnits(90071992547409.9))
        assertEquals(90071992547409.91, fromMinorUnits(9007199254740991L), 0.01)
    }

    @Test
    fun `the shared rejected price vectors are refused`() {
        val priceVector = vectors().getValue("priceVector").jsonObject

        for (element in priceVector.getValue("rejected").jsonArray) {
            val row = element.jsonObject
            val amount = row.getValue("amount").jsonPrimitive.content.toDouble()
            val reason = row.getValue("reason").jsonPrimitive.content

            val failure = runCatching { toMinorUnits(amount) }.exceptionOrNull()

            assertTrue("expected $reason to be refused", failure is SupabaseSyncException)
        }
    }

    private fun vectors() = Json.parseToJsonElement(vectorsFile().readText()).jsonObject

    private fun vectorsFile(): File {
        val base = File("").absoluteFile
        val candidates = listOf(
            File(base, "testdata/sync-wire-vectors.json"),
            File(base.parentFile, "testdata/sync-wire-vectors.json"),
            File(base, "../testdata/sync-wire-vectors.json"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("shared wire vector file not found from ${base.absolutePath}")
    }
}
