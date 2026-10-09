package app.tcg.app.scan

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import app.tcg.app.ui.scan.toOcrLines
import app.tcg.core.scan.OcrParser
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Breadth measurement of the scan OCR pipeline against clean digital card renders from
 * TCGdex (assets/cards/expected.json). This reports per-card name/number accuracy instead
 * of asserting per card: it never fails on an individual card, so a run always completes
 * and produces the summary. A clean render is easier than a real photo, so these numbers
 * are an upper bound / fast feedback loop, not a field-accuracy claim.
 */
@RunWith(AndroidJUnit4::class)
class OcrRecognitionTest {

    private data class ExpectedCard(val file: String, val name: String, val localId: String)

    private fun expectedCards(): List<ExpectedCard> {
        val context = InstrumentationRegistry.getInstrumentation().context
        val json = context.assets.open("cards/expected.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        return root.keys().asSequence().map { file ->
            val entry = root.getJSONObject(file)
            ExpectedCard(file, entry.getString("name"), entry.getString("localId"))
        }.sortedBy { it.file }.toList()
    }

    private fun normalizeNumber(value: String?): String =
        value?.trim()?.trimStart('0')?.ifEmpty { "0" } ?: "0"

    @Test
    fun measures_name_and_number_accuracy_across_card_dataset() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val cards = expectedCards()

        var nameHits = 0
        var numberHits = 0
        var bothHits = 0
        val failures = mutableListOf<String>()

        for (card in cards) {
            val bitmap = context.assets.open("cards/${card.file}").use { BitmapFactory.decodeStream(it) }
            val parsed = try {
                val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                OcrParser.parse(text.toOcrLines())
            } catch (e: Exception) {
                println("CARD ${card.file} -> ERROR ${e.message}")
                failures += "${card.file}: OCR error ${e.message} | expected=${card.name}/${card.localId}"
                continue
            }

            val nameOK = parsed.name?.trim()?.equals(card.name.trim(), ignoreCase = true) == true
            val numberOK = normalizeNumber(parsed.collectorNumber) == normalizeNumber(card.localId)
            if (nameOK) nameHits++
            if (numberOK) numberHits++
            if (nameOK && numberOK) bothHits++

            println(
                "CARD ${card.file} -> name=${parsed.name} num=${parsed.collectorNumber} " +
                    "| expected=${card.name}/${card.localId} | nameOK=$nameOK numOK=$numberOK"
            )
            if (!nameOK || !numberOK) {
                failures += "${card.file}: extracted name='${parsed.name}' num='${parsed.collectorNumber}' " +
                    "| expected name='${card.name}' num='${card.localId}'"
            }
        }

        recognizer.close()
        val total = cards.size
        fun pct(hits: Int) = if (total == 0) 0.0 else hits * 100.0 / total
        println("SUMMARY total=$total")
        println("SUMMARY nameAccuracy=${nameHits}/$total (${"%.1f".format(pct(nameHits))}%)")
        println("SUMMARY numberAccuracy=${numberHits}/$total (${"%.1f".format(pct(numberHits))}%)")
        println("SUMMARY bothCorrectAccuracy=${bothHits}/$total (${"%.1f".format(pct(bothHits))}%)")
        if (failures.isNotEmpty()) {
            println("SUMMARY failures:\n" + failures.joinToString("\n"))
        }

        // Very low floor only: the run must always complete and report numbers.
        assertTrue("expected.json should list at least one card", total > 0)
    }
}
