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
        assertEquals(0f, lines.single().left)
        assertEquals(200f, lines.single().width)
    }

    @Test
    fun empty_recognition_maps_to_empty_line_list() {
        assertTrue(Text("", emptyList<Text.TextBlock>()).toOcrLines().isEmpty())
    }

    @Test
    fun card_crop_rect_is_width_limited_for_a_portrait_frame() {
        // 600x1000 is narrower than 5:7, so the width fills and height is trimmed, centered.
        assertEquals(Rect(0, 80, 600, 920), cardCropRect(600, 1000))
    }

    @Test
    fun card_crop_rect_is_height_limited_for_a_landscape_frame() {
        // 1000x600 is wider than 5:7, so the height fills and width is trimmed, centered.
        assertEquals(Rect(286, 0, 714, 600), cardCropRect(1000, 600))
    }

    @Test
    fun card_crop_rect_stays_inside_bounds_for_a_tiny_frame() {
        val rect = cardCropRect(3, 3)
        assertEquals(Rect(0, 0, 2, 3), rect)
        assertTrue(rect.left >= 0 && rect.top >= 0 && rect.right <= 3 && rect.bottom <= 3)
    }
}
