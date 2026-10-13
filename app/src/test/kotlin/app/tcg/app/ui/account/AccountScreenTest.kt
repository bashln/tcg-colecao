package app.tcg.app.ui.account

import androidx.compose.ui.test.junit4.createComposeRule
import app.tcg.app.data.sync.LinkPreview
import app.tcg.app.data.sync.LinkPreviewSummary
import app.tcg.app.data.sync.SyncConflictDetail
import app.tcg.app.data.sync.SyncConflictResolution
import app.tcg.app.data.sync.SyncItem
import app.tcg.app.data.sync.SyncOperation
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import org.junit.Assert.assertEquals
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
        onSyncNow: () -> Unit = {},
        onResolveConflict: (String, SyncConflictResolution) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            AccountScreen(
                state = state,
                onSignIn = { _, _ -> }, onSignUp = { _, _ -> },
                onRequestLink = onRequestLink,
                onConfirmLink = onConfirmLink,
                onCancelLink = onCancelLink,
                onSyncNow = onSyncNow,
                onResolveConflict = onResolveConflict,
                onSignOut = {}, onDismissError = {},
            )
        }
    }

    private fun summary(
        localOnly: Int = 1,
        cloudOnly: Int = 0,
        identical: Int = 0,
        conflicting: Int = 0,
        duplicates: Int = 0,
    ) = LinkPreviewSummary(
        plan = LinkPreview(
            planId = "plan-1", digest = "digest-1", expiresAt = "2026-10-12T20:00:00Z",
            remoteHighWater = 7L, localOnly = List(localOnly) { "item-$it" }, cloudOnly = emptyList(),
            identical = emptyList(), conflicting = emptyList(), duplicateConfigurations = emptyList(),
            remoteItems = emptyList(),
        ),
        localOnlyCount = localOnly,
        cloudOnlyCount = cloudOnly,
        identicalCount = identical,
        conflictingCount = conflicting,
        duplicateCount = duplicates,
    )

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
            state = AccountUiState(signedIn = true, email = "a@example.com", linkState = LinkState.Preview(summary())),
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
            state = AccountUiState(signedIn = true, email = "a@example.com", linkState = LinkState.Preview(summary())),
            onConfirmLink = { confirmed = true },
        )

        compose.onNodeWithText("Confirmar e sincronizar").performClick()

        compose.runOnIdle { assertTrue(confirmed) }
    }

    @Test
    fun a_clean_preview_shows_its_counts() {
        screen(
            state = AccountUiState(
                signedIn = true, email = "a@example.com",
                linkState = LinkState.Preview(summary(localOnly = 3, cloudOnly = 2, identical = 1)),
            ),
        )
        compose.onNodeWithText("Sincronizar com sua conta?").assertExists()
        compose.onNodeWithText("Nada foi enviado até agora. Confira o que acontece ao confirmar:").assertExists()
        compose.onNodeWithText("3 posses só neste aparelho serão enviadas para a conta", substring = true).assertExists()
        compose.onNodeWithText("2 posses só na nuvem serão adicionadas aqui", substring = true).assertExists()
        compose.onNodeWithText("1 posse já existe idêntica nas duas pontas", substring = true).assertExists()
    }

    @Test
    fun linked_state_shows_pending_count_and_manual_retry() {
        var synced = 0
        screen(
            state = AccountUiState(
                signedIn = true, email = "a@example.com",
                linkState = LinkState.Linked("user-a"),
                syncStatus = SyncStatusView.Synced(pending = 2),
            ),
            onSyncNow = { synced += 1 },
        )
        compose.onNodeWithText("2 alterações aguardando envio").assertExists()
        compose.onNodeWithText("Sincronizar agora").performClick()
        compose.runOnIdle { assertEquals(1, synced) }
    }

    @Test
    fun conflicts_are_visible_with_the_item_and_a_reason() {
        screen(
            state = AccountUiState(
                signedIn = true, email = "a@example.com",
                linkState = LinkState.Linked("user-a"),
                syncStatus = SyncStatusView.Conflicts(
                    count = 1, pending = 1,
                    details = listOf(SyncConflictDetail(itemId = "item-1", reason = "stale-revision")),
                ),
            ),
        )
        compose.onNodeWithText("1 conflito de sincronização precisa da sua atenção", substring = true).assertExists()
        compose.onNodeWithText("stale-revision", substring = true).assertExists()
    }

    @Test
    fun conflict_choices_are_explicit_and_call_requested_resolution() {
        val choices = mutableListOf<Pair<String, SyncConflictResolution>>()
        screen(
            state = AccountUiState(
                signedIn = true, email = "a@example.com", linkState = LinkState.Linked("user-a"),
                syncStatus = SyncStatusView.Conflicts(1, 0, listOf(SyncConflictDetail(
                    id = "user-a:9", identity = "user-a", sequence = 9, itemId = "item-1",
                    reason = "stale-revision", local = SyncItem("item-1", "base1-4", 2, app.tcg.core.domain.model.CardVariant.NORMAL,
                        app.tcg.core.domain.model.Condition.NEAR_MINT, null, 1L),
                    remote = SyncOperation.Create("remote-op", "device-b", SyncItem("item-1", "base1-4", 3,
                        app.tcg.core.domain.model.CardVariant.NORMAL, app.tcg.core.domain.model.Condition.NEAR_MINT, null, 2L)),
                ))),
            ),
            onResolveConflict = { id, resolution -> choices += id to resolution },
        )

        compose.onNodeWithText("Manter minha coleção").performClick()
        compose.onNodeWithText("Usar versão da nuvem").assertIsNotEnabled()
        compose.onNodeWithText("Usar versão da nuvem").performClick()

        assertEquals(listOf(
            "user-a:9" to SyncConflictResolution.KeepLocal,
        ), choices)
    }

    @Test
    fun push_conflict_compares_authoritative_cloud_value_and_marks_duplicate_identity_change() {
        val detail = SyncConflictDetail(
            id = "server-id", itemId = "local-item", reason = "duplicate-configuration",
            source = "push", serverConflictId = "server-id",
            local = SyncItem("local-item", "base1-4", 2, app.tcg.core.domain.model.CardVariant.NORMAL,
                app.tcg.core.domain.model.Condition.NEAR_MINT, null, 1L),
            pushConflict = app.tcg.app.data.sync.PushConflict(
                id = "server-id", opId = "op-id", itemId = "local-item", reason = "duplicate-configuration",
                deviceId = "device-id", rejectedOperation = SyncOperation.Create("op-id", "device-id",
                    SyncItem("local-item", "base1-4", 2, app.tcg.core.domain.model.CardVariant.NORMAL,
                        app.tcg.core.domain.model.Condition.NEAR_MINT, null, 1L)),
                cloudItem = null,
                conflictingItem = app.tcg.app.data.sync.PushCloudItem.Present(
                    SyncItem("cloud-owner", "base1-4", 1, app.tcg.core.domain.model.CardVariant.NORMAL,
                        app.tcg.core.domain.model.Condition.NEAR_MINT, null, 2L), 3L,
                ), highWater = 8L, resolution = null,
            ),
        )
        screen(state = AccountUiState(
            signedIn = true, email = "a@example.com", linkState = LinkState.Linked("user-a"),
            syncStatus = SyncStatusView.Conflicts(1, 1, listOf(detail)),
        ))

        compose.onNodeWithText("Local:", substring = true).assertExists()
        compose.onNodeWithText("Nuvem:", substring = true).assertExists()
        compose.onNodeWithText("Manter minha coleção substitui o identificador local pelo identificador da nuvem.", substring = true).assertExists()
        compose.onNodeWithText("Usar versão da nuvem mantém a posse existente e remove a duplicata local.", substring = true).assertExists()
    }

    @Test
    fun permanent_push_error_explains_safe_next_step_without_resolution_choices() {
        screen(state = AccountUiState(
            signedIn = true, email = "a@example.com", linkState = LinkState.Linked("user-a"),
            syncStatus = SyncStatusView.Conflicts(1, 1, listOf(SyncConflictDetail(
                id = "permanent-id", itemId = "item-1", reason = "invalid-payload", source = "push",
                serverConflictId = "permanent-id",
            ))),
        ))

        compose.onNodeWithText("Esta operação contém dados inválidos.", substring = true).assertExists()
        compose.onNodeWithText("Manter minha coleção").assertDoesNotExist()
        compose.onNodeWithText("Usar versão da nuvem").assertDoesNotExist()
    }

    @Test
    fun unavailable_pending_count_is_not_presented_as_zero() {
        screen(
            state = AccountUiState(
                signedIn = true,
                email = "a@example.com",
                linkState = LinkState.Linked("user-a"),
                syncStatus = SyncStatusView.Failed(pending = null),
            ),
        )

        compose.onNodeWithText("Não foi possível verificar quantas alterações aguardam envio. Sua coleção local permanece segura; tente sincronizar novamente.")
            .assertExists()
    }

    @Test
    fun offline_and_error_states_are_distinguishable() {
        screen(
            state = AccountUiState(
                signedIn = true, email = "a@example.com",
                linkState = LinkState.Linked("user-a"),
                syncStatus = SyncStatusView.Offline(pending = 3),
            ),
        )
        compose.onNodeWithText("Sem conexão", substring = true).assertExists()
        compose.onNodeWithText("Nada foi perdido", substring = true).assertExists()
    }

    @Test
    fun a_blocked_preview_explains_itself_and_disables_confirmation() {
        var confirmed = false
        screen(
            state = AccountUiState(
                signedIn = true, email = "a@example.com",
                linkState = LinkState.Preview(summary(conflicting = 1)),
            ),
            onConfirmLink = { confirmed = true },
        )
        compose.onNodeWithText("1 posse tem uma versão diferente na nuvem", substring = true).assertExists()
        compose.onNodeWithText("Confirmar e sincronizar").assertIsNotEnabled()
        compose.onNodeWithText("Confirmar e sincronizar").performClick()
        compose.runOnIdle { assertFalse("a blocked preview must never confirm", confirmed) }
    }
}
