package app.tcg.app.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tcg.app.R
import app.tcg.app.update.UpdateErrorKind
import app.tcg.app.update.UpdateState

/**
 * Update screen. Checking and downloading are explicit, progress is shown for anything over a
 * moment, errors are actionable with a retry, and meaning is never conveyed by color alone. Copy
 * is honest: updates come from the public GitHub repo, and the system's security prompt for
 * out-of-Play-Store apps is explained rather than hidden.
 */
@Composable
fun UpdateScreen(
    state: UpdateState,
    installBlocked: Boolean,
    currentVersionName: String,
    onCheck: () -> Unit,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.update_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (installBlocked) {
            ErrorCard(
                message = stringResource(R.string.update_install_blocked),
                onDismiss = onDismissError,
            )
        }

        when (state) {
            UpdateState.Idle -> CheckButton(onCheck)

            UpdateState.Checking -> Text(
                stringResource(R.string.update_checking),
                style = MaterialTheme.typography.bodyMedium,
            )

            UpdateState.NoUpdate -> {
                Text(
                    stringResource(R.string.update_no_update, currentVersionName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                CheckButton(onCheck)
            }

            is UpdateState.Ready -> {
                Text(
                    stringResource(R.string.update_available_title, state.release.versionName),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.release.releaseNotes.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            state.release.releaseNotes,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_download))
                }
            }

            is UpdateState.Downloading -> {
                val total = state.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (state.received.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "update_progress_label" },
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "update_progress_label" },
                    )
                }
                Text(stringResource(R.string.update_downloading), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_cancel))
                }
            }

            is UpdateState.InstallReady -> {
                Text(
                    stringResource(R.string.update_install_ready, state.release.versionName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.update_needs_permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_install))
                }
            }

            is UpdateState.Incompatible -> {
                Text(
                    stringResource(R.string.update_incompatible),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                CheckButton(onCheck)
            }

            is UpdateState.Error -> {
                ErrorCard(
                    message = stringResource(state.kind.toMessageRes()),
                    onDismiss = onDismissError,
                )
                Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_retry))
                }
            }
        }

        Text(
            stringResource(R.string.update_source_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckButton(onCheck: () -> Unit) {
    Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.update_check))
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_dismiss))
            }
        }
    }
}

private fun UpdateErrorKind.toMessageRes(): Int = when (this) {
    UpdateErrorKind.Offline -> R.string.update_error_offline
    UpdateErrorKind.RateLimited -> R.string.update_error_rate_limited
    UpdateErrorKind.DownloadFailed -> R.string.update_error_download
    UpdateErrorKind.InvalidMetadata -> R.string.update_error_metadata
}
