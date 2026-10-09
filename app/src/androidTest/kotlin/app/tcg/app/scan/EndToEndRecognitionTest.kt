package app.tcg.app.scan

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tcg.app.ui.scan.toOcrLines
import app.tcg.core.data.tcgdex.KtorTcgdexCardSource
import app.tcg.core.data.tcgdex.TcgdexCardSource
import app.tcg.core.domain.model.Card
import app.tcg.core.scan.CandidateRanker
import app.tcg.core.scan.OcrParser
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end identification measurement: does the card the OCR pipeline extracts resolve to the
 * correct TCGdex card as the TOP ranked candidate?
 *
 * Unlike [OcrRecognitionTest] (which measures raw name/number OCR exactness), this exercises the
 * whole production path a scan takes: ML Kit OCR -> [toOcrLines] -> [OcrParser] -> live
 * [KtorTcgdexCardSource] lookups (name + number, merged and de-duped) -> [CandidateRanker].
 *
 * Two datasets are measured and reported independently, matching [OcrRecognitionTest]:
 *  - main (assets/cards): the tuned set.
 *  - holdout (assets/cards_holdout): sets the parser never saw.
 *
 * No per-card assertion: the run always completes and prints `E2E_SUMMARY[...]` lines. Requests
 * are sequential with a small delay; an individual OCR or network failure is recorded and the run
 * continues.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndRecognitionTest {

    private data class ExpectedCard(
        val file: String,
        val name: String,
        val localId: String,
        val id: String,
    )

    private data class Dataset(val label: String, val dir: String)

    private data class Summary(
        val label: String,
        val total: Int,
        val top1: Int,
        val top5: Int,
    )

    private val datasets = listOf(
        Dataset(label = "main", dir = "cards"),
        Dataset(label = "holdout", dir = "cards_holdout"),
    )

    private fun expectedCards(dir: String): List<ExpectedCard> {
        val context = InstrumentationRegistry.getInstrumentation().context
        val json = context.assets.open("$dir/expected.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        return root.keys().asSequence().map { file ->
            val entry = root.getJSONObject(file)
            ExpectedCard(file, entry.getString("name"), entry.getString("localId"), entry.getString("id"))
        }.sortedBy { it.file }.toList()
    }

    private suspend fun searchSafely(block: suspend () -> List<Card>): List<Card> =
        try {
            block()
        } catch (e: Exception) {
            println("E2E request failed: ${e.message}")
            emptyList()
        }

    private suspend fun measure(
        recognizer: TextRecognizer,
        context: Context,
        source: TcgdexCardSource,
        dataset: Dataset,
    ): Summary {
        val cards = expectedCards(dataset.dir)
        var top1Hits = 0
        var top5Hits = 0
        val misses = mutableListOf<String>()

        for (card in cards) {
            val bitmap = context.assets.open("${dataset.dir}/${card.file}").use {
                BitmapFactory.decodeStream(it)
            }
            val text = try {
                Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            } catch (e: Exception) {
                println("E2E ${card.file} -> top1=false top5=false expectedId=${card.id} gotTop=[] OCR_ERROR=${e.message}")
                misses += "${card.file}: OCR error ${e.message} | expected=${card.name}/${card.id}"
                continue
            }

            val parsed = OcrParser.parse(text.toOcrLines())
            val name = parsed.name
            val collectorNumber = parsed.collectorNumber
            val byName = if (name != null) searchSafely { source.searchByName(name) } else emptyList()
            val byNumber = if (collectorNumber != null) {
                searchSafely { source.searchByNumber(null, collectorNumber) }
            } else {
                emptyList()
            }
            val candidates = (byName + byNumber).distinctBy { it.id }
            val ranked = CandidateRanker.rank(parsed, candidates)
            val gotTop = ranked.take(5).map { it.value.id }

            val top1 = gotTop.firstOrNull() == card.id
            val top5 = gotTop.contains(card.id)
            if (top1) top1Hits++
            if (top5) top5Hits++
            if (!top1) {
                misses += "${card.file}: name='${parsed.name}' num='${parsed.collectorNumber}' " +
                    "setTotal=${parsed.setTotal} | expected=${card.id} gotTop=$gotTop"
            }

            println(
                "E2E ${card.file} -> top1=$top1 top5=$top5 expectedId=${card.id} gotTop=$gotTop " +
                    "| name='${parsed.name}' num='${parsed.collectorNumber}' setTotal=${parsed.setTotal}"
            )
            delay(150)
        }

        if (misses.isNotEmpty()) {
            println("E2E_MISSES[${dataset.label}]:\n" + misses.joinToString("\n"))
        }
        return Summary(dataset.label, cards.size, top1Hits, top5Hits)
    }

    private fun report(summary: Summary) {
        val total = summary.total
        fun pct(hits: Int) = if (total == 0) 0.0 else hits * 100.0 / total
        println(
            "E2E_SUMMARY[${summary.label}] total=$total " +
                "top1=${summary.top1}/$total (${"%.1f".format(pct(summary.top1))}%) " +
                "top5=${summary.top5}/$total (${"%.1f".format(pct(summary.top5))}%)"
        )
    }

    @Test
    fun measures_end_to_end_top_candidate_accuracy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val httpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        val source = KtorTcgdexCardSource(httpClient)

        val summaries = try {
            datasets.map { measure(recognizer, context, source, it) }
        } finally {
            recognizer.close()
            httpClient.close()
        }

        summaries.forEach { report(it) }

        summaries.forEach {
            assertTrue("${it.label} expected.json should list at least one card", it.total > 0)
        }
    }
}
