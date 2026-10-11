package app.tcg.app.ui.account

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun screen(
        state: AccountUiState,
        onConfirmLink: () -> Unit = {},
        onCancelLink: () -> Unit = {},
        onRequestLink: () -> Unit = {},
    ) {
        compose.setContent {
            AccountScreen(
                state = state,
                onSignIn = { _, _ -> }, onSignUp = { _, _ -> },
                onRequestLink = onRequestLink,
                onConfirmLink = onConfirmLink,
                onCancelLink = onCancelLink,
                onSignOut = {}, onDismissError = {},
            )
        }
    }

    @Test
    fun signed_out_renders_the_form() {
        screen(AccountUiState(signedIn = false))
        compose.onNodeWithText("Entrar").assertExists()
        compose.onNodeWithText("E-mail").assertExists()
    }

    @Test
    fun signed_in_local_only_offers_activation() {
        screen(AccountUiState(signedIn = true, email = "a@example.com", linkState = LinkState.LocalOnly))
        compose.onNodeWithText("Ativar sincronização").assertExists()
    }

    @Test
    fun cancelling_the_link_preview_does_not_confirm() {
        var confirmed = false
        var cancelled = false
        screen(
            state = AccountUiState(signedIn = true, email = "a@example.com", linkState = LinkState.AwaitingConfirmation),
            onConfirmLink = { confirmed = true },
            onCancelLink = { cancelled = true },
        )

        compose.onNodeWithText("Cancelar").performClick()

        compose.runOnIdle {
            assertTrue("cancel must be honoured", cancelled)
            assertFalse("nothing may be sent without explicit confirmation", confirmed)
        }
    }

    @Test
    fun confirming_the_link_preview_confirms_once() {
        var confirmed = false
        screen(
            state = AccountUiState(signedIn = true, email = "a@example.com", linkState = LinkState.AwaitingConfirmation),
            onConfirmLink = { confirmed = true },
        )

        compose.onNodeWithText("Confirmar e sincronizar").performClick()

        compose.runOnIdle { assertTrue(confirmed) }
    }
}
