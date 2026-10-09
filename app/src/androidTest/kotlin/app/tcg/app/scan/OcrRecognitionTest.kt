package app.tcg.app.scan

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
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
 * TCGdex. Two datasets are measured independently and reported as separate summaries:
 *
 *  - main (assets/cards): the tuned set. Reports an upper bound / fast feedback loop.
 *  - holdout (assets/cards_holdout): sets the parser never saw. A generalization check.
 *
 * Both report per-card name/number accuracy instead of asserting per card: no individual
 * card fails the run, so a run always completes and produces the summaries. A clean render
 * is easier than a real photo, so these numbers are an upper bound, not a field claim.
 */
@RunWith(AndroidJUnit4::class)
class OcrRecognitionTest {

    private data class ExpectedCard(val file: String, val name: String, val localId: String)

    private data class Dataset(val label: String, val dir: String, val cardTag: String)

    private data class Summary(
        val label: String,
        val total: Int,
        val nameHits: Int,
        val numberHits: Int,
        val bothHits: Int,
        val failures: List<String>,
    )

    private val datasets = listOf(
        Dataset(label = "main", dir = "cards", cardTag = "CARD"),
        Dataset(label = "holdout", dir = "cards_holdout", cardTag = "HOLDOUT_CARD"),
    )

    private fun expectedCards(dir: String): List<ExpectedCard> {
        val context = InstrumentationRegistry.getInstrumentation().context
        val json = context.assets.open("$dir/expected.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        return root.keys().asSequence().map { file ->
            val entry = root.getJSONObject(file)
            ExpectedCard(file, entry.getString("name"), entry.getString("localId"))
        }.sortedBy { it.file }.toList()
    }

    private fun normalizeNumber(value: String?): String =
        value?.trim()?.trimStart('0')?.ifEmpty { "0" } ?: "0"

    private fun measure(
        recognizer: TextRecognizer,
        context: Context,
        dataset: Dataset,
    ): Summary {
        val cards = expectedCards(dataset.dir)
        var nameHits = 0
        var numberHits = 0
        var bothHits = 0
        val failures = mutableListOf<String>()

        for (card in cards) {
            val bitmap = context.assets.open("${dataset.dir}/${card.file}").use {
                BitmapFactory.decodeStream(it)
            }
            val text = try {
                Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            } catch (e: Exception) {
                println("${dataset.cardTag} ${card.file} -> ERROR ${e.message}")
                failures += "${card.file}: OCR error ${e.message} | expected=${card.name}/${card.localId}"
                continue
            }
            val ocrLines = text.toOcrLines()
            val parsed = OcrParser.parse(ocrLines)
            val nameOK = parsed.name?.trim()?.equals(card.name.trim(), ignoreCase = true) == true
            val numberOK = normalizeNumber(parsed.collectorNumber) == normalizeNumber(card.localId)
            if (nameOK) nameHits++
            if (numberOK) numberHits++
            if (nameOK && numberOK) bothHits++

            println(
                "${dataset.cardTag} ${card.file} -> name=${parsed.name} num=${parsed.collectorNumber} " +
                    "| expected=${card.name}/${card.localId} | nameOK=$nameOK numOK=$numberOK"
            )
            if (!nameOK || !numberOK) {
                println("DEBUG_LINES ${card.file}:")
                ocrLines.forEach { println("   [top=${it.top}, left=${it.left}, h=${it.height}] '${it.text}'") }
                failures += "${card.file}: extracted name='${parsed.name}' num='${parsed.collectorNumber}' " +
                    "| expected name='${card.name}' num='${card.localId}'"
            }
        }

        return Summary(dataset.label, cards.size, nameHits, numberHits, bothHits, failures)
    }

    private fun report(summary: Summary) {
        val label = summary.label
        val total = summary.total
        fun pct(hits: Int) = if (total == 0) 0.0 else hits * 100.0 / total
        println("SUMMARY[$label] total=$total")
        println("SUMMARY[$label] nameAccuracy=${summary.nameHits}/$total (${"%.1f".format(pct(summary.nameHits))}%)")
        println("SUMMARY[$label] numberAccuracy=${summary.numberHits}/$total (${"%.1f".format(pct(summary.numberHits))}%)")
        println("SUMMARY[$label] bothCorrectAccuracy=${summary.bothHits}/$total (${"%.1f".format(pct(summary.bothHits))}%)")
        if (summary.failures.isNotEmpty()) {
            println("SUMMARY[$label] failures:\n" + summary.failures.joinToString("\n"))
        }
    }

    @Test
    fun measures_name_and_number_accuracy_across_card_datasets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val summaries = datasets.map { measure(recognizer, context, it) }
        recognizer.close()

        summaries.forEach { report(it) }

        // Very low floor only: each run must always complete and report numbers.
        summaries.forEach {
            assertTrue("${it.label} expected.json should list at least one card", it.total > 0)
        }
    }
}
