package app.tcg.app.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tcg.app.auth.AuthController
import app.tcg.app.auth.AuthError
import app.tcg.app.auth.AuthResult
import app.tcg.app.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether the signed-in account is linked to sync, and whether a preview is pending confirmation. */
sealed interface LinkState {
    data object LocalOnly : LinkState
    data object AwaitingConfirmation : LinkState
    data class Linked(val identity: String) : LinkState
}

/** Coarse, user-presentable error; the Compose layer maps it to a localized string. */
enum class AccountError {
    INVALID_CREDENTIALS,
    EMAIL_ALREADY_REGISTERED,
    EMAIL_NOT_CONFIRMED,
    NETWORK,
    UNKNOWN,
}

data class AccountUiState(
    val signedIn: Boolean = false,
    val email: String? = null,
    val submitting: Boolean = false,
    val awaitingVerificationEmail: String? = null,
    val error: AccountError? = null,
    val linkState: LinkState = LinkState.LocalOnly,
)

/**
 * Drives the account screen. Signing in never auto-links sync: [LinkState.AwaitingConfirmation]
 * is an explicit preview the person must confirm before [onLinkConfirmed] binds the identity.
 * Cancelling keeps local-only state; signing out clears the link and calls [onLinkUnlinked]
 * while the local collection is untouched. Switching accounts resets to local-only so a new
 * preview is always required and identities are never mixed.
 */
class AccountViewModel(
    private val auth: AuthController,
    private val onLinkConfirmed: (String) -> Unit,
    private val onLinkUnlinked: () -> Unit,
) : ViewModel() {

    private val submitting = MutableStateFlow(false)
    private val error = MutableStateFlow<AccountError?>(null)
    private val linkState = MutableStateFlow<LinkState>(LinkState.LocalOnly)

    val state: StateFlow<AccountUiState> = combine(
        auth.state,
        submitting,
        error,
        linkState,
    ) { session, isSubmitting, currentError, link ->
        AccountUiState(
            signedIn = session is AuthState.SignedIn,
            email = session.emailOrNull(),
            submitting = isSubmitting,
            awaitingVerificationEmail = (session as? AuthState.AwaitingVerification)?.email,
            error = currentError,
            linkState = link,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AccountUiState())

    fun submitSignIn(email: String, password: String) = submit { auth.signIn(email, password) }

    fun submitSignUp(email: String, password: String) = submit { auth.signUp(email, password) }

    fun requestLink() {
        if (auth.state.value !is AuthState.SignedIn) return
        if (linkState.value is LinkState.LocalOnly) {
            linkState.value = LinkState.AwaitingConfirmation
        }
    }

    fun confirmLink() {
        val session = auth.state.value as? AuthState.SignedIn ?: return
        if (linkState.value !is LinkState.AwaitingConfirmation) return
        linkState.value = LinkState.Linked(session.session.userId)
        onLinkConfirmed(session.session.userId)
    }

    fun cancelLink() {
        if (linkState.value is LinkState.AwaitingConfirmation) {
            linkState.value = LinkState.LocalOnly
        }
    }

    fun signOut() {
        viewModelScope.launch {
            auth.signOut()
            linkState.value = LinkState.LocalOnly
            error.value = null
            onLinkUnlinked()
        }
    }

    fun dismissError() {
        error.value = null
    }

    private fun submit(block: suspend () -> AuthResult) {
        viewModelScope.launch {
            submitting.value = true
            error.value = null
            when (val result = block()) {
                is AuthResult.Success -> Unit
                is AuthResult.Failure -> error.value = result.error.toAccountError()
            }
            submitting.value = false
        }
    }

    private fun AuthState.emailOrNull(): String? = when (this) {
        is AuthState.SignedIn -> session.email
        is AuthState.AwaitingVerification -> email
        AuthState.SignedOut -> null
    }

    private fun AuthError.toAccountError(): AccountError = when (this) {
        AuthError.InvalidCredentials -> AccountError.INVALID_CREDENTIALS
        AuthError.EmailAlreadyRegistered -> AccountError.EMAIL_ALREADY_REGISTERED
        AuthError.EmailNotConfirmed -> AccountError.EMAIL_NOT_CONFIRMED
        AuthError.Network -> AccountError.NETWORK
        is AuthError.Unknown -> AccountError.UNKNOWN
    }
}
