package app.tcg.app.ui.theme

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContractTest {

    @Test
    fun light_and_dark_themes_have_sufficient_contrast_and_distinct_surfaces() {
        assertNotEquals(LightColors.surface, LightColors.surfaceVariant)
        assertNotEquals(DarkColors.surface, DarkColors.surfaceVariant)
        
        assertNotEquals(LightColors.surfaceVariant, LightColors.outline)
        assertNotEquals(DarkColors.surfaceVariant, DarkColors.outline)
        
        assertTrue(LightColors.background.red > 0.8f && LightColors.background.green > 0.8f)
        assertTrue(DarkColors.background.red < 0.2f && DarkColors.background.green < 0.2f)
    }
}
