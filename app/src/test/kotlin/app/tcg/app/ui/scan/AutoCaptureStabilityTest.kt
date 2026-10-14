package app.tcg.app.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import app.tcg.core.domain.model.OcrLine

class AutoCaptureStabilityTest {
    @Test
    fun only_ocr_lines_inside_card_guide_contribute_to_identity() {
        val lines = listOf(
            OcrLine("noise outside", top = 10f, height = 30f, left = 50f, width = 200f),
            OcrLine("Meowth", top = 150f, height = 50f, left = 300f, width = 250f),
            OcrLine("161/ Rocket", top = 820f, height = 35f, left = 300f, width = 250f),
        )

        assertEquals(
            listOf("Meowth", "161/ Rocket"),
            linesInsideCardFrame(lines, imageWidth = 1_000, imageHeight = 1_000).map { it.text },
        )
    }
    @Test
    fun captures_only_after_same_identity_stays_stable_for_two_seconds() {
        val stability = AutoCaptureStability(stableForMillis = 2_000)

        assertNull(stability.observe("Froakie|23", 1_000))
        assertNull(stability.observe("Froakie|23", 2_999))
        assertEquals("Froakie|23", stability.observe("Froakie|23", 3_000))
        assertNull(stability.observe("Froakie|23", 3_500))
    }

    @Test
    fun tolerates_ocr_prefix_noise_without_treating_it_as_a_new_card() {
        val stability = AutoCaptureStability(stableForMillis = 2_000)

        assertNull(stability.observe("cass meowth da equipe rocket", 1_000))
        assertNull(stability.observe("gasmeowth a equipe", 1_500))
        assertNull(stability.observe("asmeowth a equipe rocket", 2_000))
        assertEquals("gasmeowth a equipe", stability.observe("gasmeowth a equipe", 3_000))
    }

    @Test
    fun unrelated_names_restart_the_stability_window() {
        val stability = AutoCaptureStability(stableForMillis = 2_000)

        stability.observe("Meowth da Equipe Rocket", 1_000)
        assertNull(stability.observe("Charizard ex", 2_500))
        assertNull(stability.observe("Charizard ex", 4_499))
        assertEquals("Charizard ex", stability.observe("Charizard ex", 4_500))
    }

    @Test
    fun short_name_survives_one_character_ocr_misread() {
        val stability = AutoCaptureStability(stableForMillis = 2_000)

        assertNull(stability.observe("Pikachu", 1_000))
        assertNull(stability.observe("Pikxchu", 2_000))
        assertEquals("Pikachu", stability.observe("Pikachu", 3_000))
    }

    @Test
    fun changed_or_missing_identity_restarts_stability_window() {
        val stability = AutoCaptureStability(stableForMillis = 2_000)

        stability.observe("Froakie|23", 1_000)
        assertNull(stability.observe("Frogadier|24", 2_500))
        assertNull(stability.observe(null, 3_000))
        assertNull(stability.observe("Frogadier|24", 3_500))
        assertEquals("Frogadier|24", stability.observe("Frogadier|24", 5_500))
    }
}
