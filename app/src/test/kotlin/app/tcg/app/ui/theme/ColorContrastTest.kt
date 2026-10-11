package app.tcg.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorContrastTest {
    @Test
    fun light_scheme_keeps_normal_text_above_wcag_aa_contrast() {
        assertTextPairsMeetWcagAa(LightColors)
    }

    @Test
    fun dark_scheme_keeps_normal_text_above_wcag_aa_contrast() {
        assertTextPairsMeetWcagAa(DarkColors)
    }

    private fun assertTextPairsMeetWcagAa(scheme: ColorScheme) {
        listOf(
            "primary" to (scheme.onPrimary to scheme.primary),
            "primary container" to (scheme.onPrimaryContainer to scheme.primaryContainer),
            "secondary" to (scheme.onSecondary to scheme.secondary),
            "secondary container" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
            "tertiary" to (scheme.onTertiary to scheme.tertiary),
            "tertiary container" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
            "error" to (scheme.onError to scheme.error),
            "error container" to (scheme.onErrorContainer to scheme.errorContainer),
            "surface" to (scheme.onSurface to scheme.surface),
            "surface variant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
            "background" to (scheme.onBackground to scheme.background),
        ).forEach { (name, colors) ->
            assertTrue(
                "$name contrast is ${colors.first.contrastRatio(colors.second)}:1",
                colors.first.contrastRatio(colors.second) >= 4.5,
            )
        }
    }

    private fun Color.contrastRatio(other: Color): Double {
        val first = relativeLuminance()
        val second = other.relativeLuminance()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    private fun Color.relativeLuminance(): Double {
        val argb = toArgb()
        val red = ((argb shr 16) and 0xFF) / 255.0
        val green = ((argb shr 8) and 0xFF) / 255.0
        val blue = (argb and 0xFF) / 255.0

        fun linearize(channel: Double): Double =
            if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

        return 0.2126 * linearize(red) + 0.7152 * linearize(green) + 0.0722 * linearize(blue)
    }
}
