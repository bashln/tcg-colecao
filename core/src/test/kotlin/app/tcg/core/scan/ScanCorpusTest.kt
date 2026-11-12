package app.tcg.core.scan

import app.tcg.core.domain.model.OcrLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Line sets read off real card photos, kept as a corpus so a change to the parser has to
 * answer for cards that already failed once. Each case names the photo it came from.
 */
class ScanCorpusTest {
    private fun line(text: String, top: Float, left: Float, height: Float = 22f) =
        OcrLine(text, top, height, left, 300f)

    // Pikachu, 30th anniversary, 027/128, stamp 05/30, PS 80, Soneca.
    private val pika027 = listOf(
        line("BÁSICO", 180f, 220f),
        line("Pikachu", 175f, 330f, 54f),
        line("PS 80", 180f, 900f, 40f),
        line("Nº 0025. Pokémon Rato. Altura: 0,4 m. Peso: 6,0 kg", 735f, 300f),
        line("Soneca", 880f, 420f, 40f),
        line("Cure 30 pontos de dano deste Pokémon.", 930f, 270f),
        line("Fraqueza ×2", 1210f, 230f),
        line("Ilust. Asako Ito", 1265f, 240f),
        line("J 30ct 027/128 05/30", 1270f, 330f),
        line("Tem pequenas bolsas elétricas em suas bochechas.", 1310f, 470f),
    )

    // Same card when OCR splits the stamp into its own line to the right of the number.
    private val pika027Split = pika027.filterNot { it.text.startsWith("J 30ct") } + listOf(
        line("05/30", 1270f, 620f),
        line("027/128", 1270f, 380f),
    )

    // Pikachu, 30th anniversary, 048/128, stamp 26/30, PS 70, Armazenar.
    private val pika048Split = listOf(
        line("BÁSICO", 185f, 230f),
        line("Pikachu", 180f, 340f, 54f),
        line("PS 70", 185f, 900f, 40f),
        line("Nº 0025. Pokémon Rato. Altura: 0,4 m. Peso: 6,0 kg", 720f, 300f),
        line("Armazenar", 860f, 400f, 40f),
        line("Coloque até 2 cartas de Energia Básica da sua pilha de", 905f, 260f),
        line("descarte na sua mão.", 935f, 260f),
        line("Fraqueza ×2", 1200f, 230f),
        line("Ilust. KIYOTAKA OSHIYAMA", 1255f, 240f),
        line("26/30", 1265f, 640f),
        line("048/128", 1265f, 360f),
        line("Tem pequenas bolsas elétricas em suas bochechas.", 1300f, 470f),
    )

    @Test fun pikachu_027_reads_its_own_number_and_total() {
        val parsed = OcrParser.parse(pika027)

        assertEquals("Pikachu", parsed.name)
        assertEquals("027", parsed.collectorNumber)
        assertEquals(128, parsed.setTotal)
        assertEquals(80, parsed.hp)
        assertNull(parsed.promoCode)
    }

    @Test fun pikachu_027_ignores_the_stamp_when_ocr_reads_it_first() {
        val parsed = OcrParser.parse(pika027Split)

        assertEquals("Pikachu", parsed.name)
        assertEquals("027", parsed.collectorNumber)
        assertEquals(128, parsed.setTotal)
    }

    @Test fun pikachu_048_ignores_its_stamp() {
        val parsed = OcrParser.parse(pika048Split)

        assertEquals("Pikachu", parsed.name)
        assertEquals("048", parsed.collectorNumber)
        assertEquals(128, parsed.setTotal)
        assertEquals(70, parsed.hp)
        assertNull(parsed.promoCode)
    }
}
