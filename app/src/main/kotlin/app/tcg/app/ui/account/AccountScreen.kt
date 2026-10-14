package app.tcg.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.tcg.app.R
import app.tcg.app.data.sync.LinkPreviewSummary
import app.tcg.app.data.sync.SyncConflictResolution

/**
 * Account screen: create an account or sign in, and explicitly activate sync. The local
 * collection is always available; linking is a separate, confirmed step so nothing is sent
 * to an account until the person reviews the preview and confirms.
 */
@Composable
fun AccountScreen(
    state: AccountUiState,
    onSignIn: (email: String, password: String) -> Unit,
    onSignUp: (email: String, password: String) -> Unit,
    onRequestLink: () -> Unit,
    onConfirmLink: () -> Unit,
    onCancelLink: () -> Unit,
    onSyncNow: () -> Unit,
    onResolveConflict: (String, SyncConflictResolution) -> Unit = { _, _ -> },
    onSignOut: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.signedIn) {
        SignedInAccount(
            state = state,
            onRequestLink = onRequestLink,
            onConfirmLink = onConfirmLink,
            onCancelLink = onCancelLink,
            onSyncNow = onSyncNow,
            onResolveConflict = onResolveConflict,
            onSignOut = onSignOut,
            modifier = modifier,
        )
    } else {
        SignedOutAccount(
            state = state,
            onSignIn = onSignIn,
            onSignUp = onSignUp,
            onDismissError = onDismissError,
            modifier = modifier,
        )
    }
}

@Composable
private fun SignedOutAccount(
    state: AccountUiState,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isSignUp by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.account_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.account_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.awaitingVerificationEmail?.let { pendingEmail ->
            Text(
                text = stringResource(R.string.account_verification_sent, pendingEmail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        state.error?.let { error ->
            ErrorText(text = state.errorMessage ?: stringResource(error.toReadableStringRes()), onDismiss = onDismissError)
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.account_email)) },
            singleLine = true,
            enabled = !state.submitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.account_password)) },
            singleLine = true,
            enabled = !state.submitting,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                if (isSignUp) onSignUp(email.trim(), password) else onSignIn(email.trim(), password)
            },
            enabled = !state.submitting && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    stringResource(
                        if (isSignUp) R.string.account_sign_up else R.string.account_sign_in,
                    ),
                )
            }
        }

        TextButton(
            onClick = { isSignUp = !isSignUp },
            enabled = !state.submitting,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                stringResource(
                    if (isSignUp) R.string.account_switch_to_sign_in else R.string.account_switch_to_sign_up,
                ),
            )
        }
    }
}

@Composable
private fun SignedInAccount(
    state: AccountUiState,
    onRequestLink: () -> Unit,
    onConfirmLink: () -> Unit,
    onCancelLink: () -> Unit,
    onSyncNow: () -> Unit,
    onResolveConflict: (String, SyncConflictResolution) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.account_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = state.email.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
        )

        when (val link = state.linkState) {
            LinkState.LocalOnly -> {
                Text(
                    text = stringResource(R.string.account_local_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onRequestLink, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.account_activate_sync))
                }
            }

            is LinkState.Preview -> LinkPreviewSection(
                summary = link.summary,
                confirming = state.linking,
                onConfirm = onConfirmLink,
                onCancel = onCancelLink,
            )

            is LinkState.Linked -> {
                Text(
                    text = stringResource(R.string.account_linked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                SyncStatusPanel(state.syncStatus, onSyncNow, onResolveConflict)
                OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.account_sign_out))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.linkState !is LinkState.Linked) {
            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.account_sign_out))
            }
        }
    }
}

/** Visible sync state for a linked account: pending, conflicts with reasons, offline, error. */
@Composable
private fun SyncStatusPanel(
    status: SyncStatusView?,
    onSyncNow: () -> Unit,
    onResolveConflict: (String, SyncConflictResolution) -> Unit,
) {
    var resolvingConflictId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(status) { resolvingConflictId = null }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = when (status) {
                null -> stringResource(R.string.account_sync_idle)
                SyncStatusView.Running -> stringResource(R.string.account_sync_running)
                is SyncStatusView.Synced -> if (status.pending == 0) {
                    stringResource(R.string.account_sync_synced)
                } else {
                    pluralStringResource(R.plurals.account_sync_pending, status.pending, status.pending)
                }
                is SyncStatusView.Conflicts -> if (status.pending == null) {
                    stringResource(R.string.account_sync_count_unavailable)
                } else pluralStringResource(
                    R.plurals.account_sync_conflicts,
                    status.count,
                    status.count,
                )
                is SyncStatusView.Offline -> status.pending?.let { stringResource(R.string.account_sync_offline, it) }
                    ?: stringResource(R.string.account_sync_count_unavailable)
                is SyncStatusView.Failed -> status.pending?.let { stringResource(R.string.account_sync_error, it) }
                    ?: stringResource(R.string.account_sync_count_unavailable)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when (status) {
                is SyncStatusView.Conflicts, is SyncStatusView.Failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (status is SyncStatusView.Conflicts) {
            status.details.forEach { detail ->
                Text(
                    text = stringResource(R.string.account_sync_conflict_row, detail.itemId, detail.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
                val remoteItem = when (val remote = detail.remote) {
                    is app.tcg.app.data.sync.SyncOperation.Create -> remote.item
                    is app.tcg.app.data.sync.SyncOperation.Replace -> remote.item
                    is app.tcg.app.data.sync.SyncOperation.Delete, null -> null
                }
                val push = detail.pushConflict
                val cloudItem = when (val cloud = push?.cloudItem) {
                    is app.tcg.app.data.sync.PushCloudItem.Present -> cloud.item
                    else -> remoteItem
                }
                if (detail.local != null || cloudItem != null || push != null) {
                    Text(
                        text = stringResource(
                            R.string.account_sync_conflict_values,
                            detail.local?.let { "${it.quantity} × ${it.cardId} · ${it.variant}/${it.condition}" } ?: stringResource(R.string.account_sync_value_absent),
                            cloudItem?.let { "${it.quantity} × ${it.cardId} · ${it.variant}/${it.condition}" } ?: stringResource(R.string.account_sync_value_absent),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (detail.reason == "duplicate-configuration") {
                    Text("Manter minha coleção substitui o identificador local pelo identificador da nuvem. Usar versão da nuvem mantém a posse existente e remove a duplicata local.")
                }
                val permanentError = when (detail.reason) {
                    "invalid-payload" -> "Esta operação contém dados inválidos. Sua coleção local está salva; atualize a posse manualmente antes de sincronizar novamente."
                    "device-revoked" -> "Este dispositivo perdeu autorização para sincronizar. Sua coleção local está salva; entre novamente na conta e tente sincronizar."
                    "revision-limit" -> "Esta posse atingiu o limite de revisões do servidor. Nenhuma versão será escolhida automaticamente; sua coleção local permanece salva."
                    else -> null
                }
                if (permanentError != null) Text(permanentError, color = MaterialTheme.colorScheme.error)
                val resolvable = push?.rejectedOperation != null && detail.reason in setOf("stale", "missing", "deleted", "id-collision", "duplicate-configuration")
                    || detail.source != "push" && detail.remote != null
                if (detail.id.isNotBlank() && resolvable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { resolvingConflictId = detail.id; onResolveConflict(detail.id, SyncConflictResolution.KeepLocal) },
                            enabled = resolvingConflictId == null,
                        ) { Text(stringResource(R.string.account_sync_keep_local)) }
                        OutlinedButton(
                            onClick = { resolvingConflictId = detail.id; onResolveConflict(detail.id, SyncConflictResolution.KeepCloud) },
                            enabled = resolvingConflictId == null,
                        ) { Text(stringResource(R.string.account_sync_keep_cloud)) }
                    }
                    if (resolvingConflictId == detail.id) Text("Aplicando escolha…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
        OutlinedButton(onClick = onSyncNow, enabled = status !is SyncStatusView.Running, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (status is SyncStatusView.Running) {
                    stringResource(R.string.account_sync_running_button)
                } else {
                    stringResource(R.string.account_sync_now)
                },
            )
        }
    }
}

@Composable
private fun LinkPreviewSection(
    summary: LinkPreviewSummary,
    confirming: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val plan = summary.plan
    val blocked = summary.conflictingCount > 0 || summary.duplicateCount > 0
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.account_link_preview_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.account_link_preview_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PreviewRow(
            text = pluralStringResource(
                R.plurals.account_link_local_only,
                summary.localOnlyCount,
                summary.localOnlyCount,
            ),
        )
        PreviewRow(
            text = pluralStringResource(
                R.plurals.account_link_cloud_only,
                summary.cloudOnlyCount,
                summary.cloudOnlyCount,
            ),
        )
        PreviewRow(
            text = pluralStringResource(
                R.plurals.account_link_identical,
                summary.identicalCount,
                summary.identicalCount,
            ),
        )
        if (summary.conflictingCount > 0) {
            PreviewRow(
                text = pluralStringResource(
                    R.plurals.account_link_conflicting,
                    summary.conflictingCount,
                    summary.conflictingCount,
                ),
                isError = true,
            )
        }
        if (summary.duplicateCount > 0) {
            PreviewRow(
                text = pluralStringResource(
                    R.plurals.account_link_duplicates,
                    summary.duplicateCount,
                    summary.duplicateCount,
                ),
                isError = true,
            )
        }
        if (blocked) {
            Text(
                text = stringResource(R.string.account_link_blocked_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = onConfirm,
            enabled = !confirming && !blocked,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (confirming) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.account_link_confirm))
            }
        }
        OutlinedButton(onClick = onCancel, enabled = !confirming, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.account_link_cancel))
        }
        Text(
            text = stringResource(R.string.account_link_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewRow(text: String, isError: Boolean = false) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorText(text: String, onDismiss: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
    // Allow clearing the error by tapping it; keeps the affordance without extra chrome.
    TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.account_dismiss_error))
    }
}

private fun AccountError.toReadableStringRes(): Int = when (this) {
    AccountError.INVALID_CREDENTIALS -> R.string.account_error_invalid_credentials
    AccountError.EMAIL_ALREADY_REGISTERED -> R.string.account_error_email_registered
    AccountError.EMAIL_NOT_CONFIRMED -> R.string.account_error_email_not_confirmed
    AccountError.NETWORK -> R.string.account_error_network
    AccountError.LINK_BLOCKED -> R.string.account_error_link_blocked
    AccountError.LINK_STALE -> R.string.account_error_link_stale
    AccountError.LINK_PARTIAL -> R.string.account_error_link_partial
    AccountError.UNKNOWN -> R.string.account_error_unknown
}
