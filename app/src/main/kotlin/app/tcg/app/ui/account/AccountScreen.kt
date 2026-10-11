package app.tcg.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.tcg.app.R

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
            ErrorText(text = stringResource(error.toReadableStringRes()), onDismiss = onDismissError)
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

            LinkState.AwaitingConfirmation -> LinkPreviewDialog(onConfirm = onConfirmLink, onCancel = onCancelLink)

            is LinkState.Linked -> {
                Text(
                    text = stringResource(R.string.account_linked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
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

@Composable
private fun LinkPreviewDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.account_link_preview_title)) },
        text = { Text(stringResource(R.string.account_link_preview_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.account_link_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.account_link_cancel))
            }
        },
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
    AccountError.UNKNOWN -> R.string.account_error_unknown
}
