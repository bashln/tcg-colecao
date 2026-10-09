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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrRecognitionTest {

    private data class ExpectedCard(val file: String, val name: String, val number: String)

    // Names and numbers read from https://api.tcgdex.net/v2/pt/cards/<id>.
    private val cards = listOf(
        ExpectedCard("furret.png", "Furret", "136"),
        ExpectedCard("charizard_ex.png", "Charizard ex", "006"),
        ExpectedCard("toedscool.png", "Toedscool", "025"),
    )

    @Test
    fun parses_expected_name_and_number_from_real_card_images() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val failures = mutableListOf<String>()
        for (card in cards) {
            val bitmap = context.assets.open("cards/${card.file}").use { BitmapFactory.decodeStream(it) }
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            val parsed = OcrParser.parse(text.toOcrLines())

            println("CARD ${card.file} -> name=${parsed.name} number=${parsed.collectorNumber} expected=${card.name}/${card.number}")

            if (parsed.name != card.name) {
                failures += "${card.file}: name '${parsed.name}' != '${card.name}'"
            }
            if (parsed.collectorNumber != card.number) {
                failures += "${card.file}: number '${parsed.collectorNumber}' != '${card.number}'"
            }
        }

        assertTrue("OCR mismatches:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
