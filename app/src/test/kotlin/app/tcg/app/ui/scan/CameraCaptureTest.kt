package app.tcg.app.ui.scan

import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CameraCaptureTest {

    @Test
    fun maps_ml_kit_lines_to_ocr_lines_with_vertical_geometry() {
        val line = Text.Line(
            "Furret",
            Rect(0, 100, 200, 140),
            emptyList<Point>(),
            "pt",
            Matrix(),
            emptyList<Text.Element>(),
            0f,
            0f,
        )
        val block = Text.TextBlock(
            "Furret",
            Rect(0, 100, 200, 140),
            emptyList<Point>(),
            "pt",
            Matrix(),
            listOf(line),
        )

        val lines = Text("Furret", listOf(block)).toOcrLines()

        assertEquals(1, lines.size)
        assertEquals("Furret", lines.single().text)
        assertEquals(100f, lines.single().top)
        assertEquals(40f, lines.single().height)
    }

    @Test
    fun empty_recognition_maps_to_empty_line_list() {
        assertTrue(Text("", emptyList<Text.TextBlock>()).toOcrLines().isEmpty())
    }
}
