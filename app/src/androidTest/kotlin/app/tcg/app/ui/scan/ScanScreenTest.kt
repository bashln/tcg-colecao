package app.tcg.app.ui.scan

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tcg.app.ui.theme.TcgTheme
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Scored
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deterministic tests for the scan surface. [ScanContent] is the stateless UI, so these run
 * without a camera or network while still exercising the real layout and strings.
 */
@RunWith(AndroidJUnit4::class)
class ScanScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun first_launch_shows_manual_search_entry_point() {
        val state = mutableStateOf(ScanUiState())
        rule.setContent {
            TcgTheme {
                ScanContent(
                    state = state.value,
                    manualQuery = "",
                    onManualQueryChange = {},
                    onManualSearch = {},
                    onCapture = {},
                    onGallery = {},
                    onCardSelected = {},
                )
            }
        }

        rule.onNodeWithText("Nenhuma carta ainda").assertIsDisplayed()
        // The copy and the control now agree: the empty state names and offers the entry point.
        rule.onNodeWithText("Buscar por nome").assertIsDisplayed()
        rule.onNodeWithContentDescription("Buscar por nome").assertExists()
    }

    @Test
    fun entering_name_and_searching_yields_candidates() {
        val state = mutableStateOf(ScanUiState())
        val query = mutableStateOf("")
        var searched: String? = null
        rule.setContent {
            TcgTheme {
                ScanContent(
                    state = state.value,
                    manualQuery = query.value,
                    onManualQueryChange = { query.value = it },
                    onManualSearch = {
                        searched = query.value
                        state.value = ScanUiState(candidates = listOf(Scored(furret(), 100)))
                    },
                    onCapture = {},
                    onGallery = {},
                    onCardSelected = {},
                )
            }
        }

        rule.onNodeWithText("Buscar por nome").performClick()
        rule.onNodeWithText("Nome da carta").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextInput("Furr")
        rule.onNodeWithText("Buscar").performClick()
        rule.waitForIdle()

        assertEquals("Furr", searched)
        rule.onNodeWithText("Furret").assertIsDisplayed()
    }

    @Test
    fun failed_scan_auto_reveals_manual_search_with_plain_language() {
        val state = mutableStateOf(
            ScanUiState(error = "Não foi possível buscar agora.", manualSearch = true),
        )
        rule.setContent {
            TcgTheme {
                ScanContent(
                    state = state.value,
                    manualQuery = "",
                    onManualQueryChange = {},
                    onManualSearch = {},
                    onCapture = {},
                    onGallery = {},
                    onCardSelected = {},
                )
            }
        }

        rule.onNodeWithText("Não encontramos a carta").assertIsDisplayed()
        rule.onNodeWithText("Nome da carta").assertIsDisplayed()
    }

    private fun furret() = Card(
        id = "swsh3-136",
        name = "Furret",
        setId = "swsh3",
        setName = "Escuridão Incandescente",
        collectorNumber = "136",
        rarity = "Incomum",
        hp = 110,
        types = listOf("Incolor"),
        attacks = emptyList(),
        imageUrl = null,
        variants = setOf(CardVariant.NORMAL),
        prices = emptyList(),
    )
}
