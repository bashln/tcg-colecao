package app.tcg.app.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import app.tcg.app.auth.AuthController
import app.tcg.app.auth.AuthError
import app.tcg.app.auth.AuthResult
import app.tcg.app.auth.AuthState
import app.tcg.app.data.sync.LinkOutcome
import app.tcg.app.data.sync.LinkPreviewSummary
import app.tcg.app.data.sync.SyncConflictDetail
import app.tcg.app.data.sync.SyncLinkCoordinator
import app.tcg.app.data.sync.SyncResult
import app.tcg.app.data.sync.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether the signed-in account is linked to sync, and whether a preview is pending confirmation. */
sealed interface LinkState {
    data object LocalOnly : LinkState
    /** A server preview is on screen; nothing is uploaded until the person confirms. */
    data class Preview(val summary: LinkPreviewSummary) : LinkState
    data class Linked(val identity: String) : LinkState
}

/** Coarse, user-presentable error; the Compose layer maps it to a localized string. */
enum class AccountError {
    INVALID_CREDENTIALS,
    EMAIL_ALREADY_REGISTERED,
    EMAIL_NOT_CONFIRMED,
    NETWORK,
    /** The preview is blocked by rows the person must resolve before linking. */
    LINK_BLOCKED,
    /** The local collection or the cloud changed after the preview; a new one is required. */
    LINK_STALE,
    /** A commit may exist in the cloud, but this device did not finish linking locally. */
    LINK_PARTIAL,
    UNKNOWN,
}

/** User-presentable sync state; null while nothing is bound to this account. */
sealed interface SyncStatusView {
    data object Running : SyncStatusView
    data class Synced(val pending: Int) : SyncStatusView
    data class Conflicts(val count: Int, val pending: Int, val details: List<SyncConflictDetail>) : SyncStatusView
    data class Offline(val pending: Int) : SyncStatusView
    data class Failed(val pending: Int) : SyncStatusView
}

data class AccountUiState(
    val signedIn: Boolean = false,
    val email: String? = null,
    val submitting: Boolean = false,
    val linking: Boolean = false,
    val awaitingVerificationEmail: String? = null,
    val error: AccountError? = null,
    val errorMessage: String? = null,
    val linkState: LinkState = LinkState.LocalOnly,
    val syncStatus: SyncStatusView? = null,
)

/**
 * Drives the account screen. Signing in never auto-links sync: [LinkState.Preview] is a
 * real server preview the person must confirm before [SyncLinkCoordinator.confirm] binds
 * the identity. Cancelling keeps local-only state; signing out clears the link and calls
 * [onLinkUnlinked] while the local collection is untouched. Switching accounts resets to
 * local-only so a new preview is always required and identities are never mixed.
 */
class AccountViewModel(
    private val auth: AuthController,
    private val link: SyncLinkCoordinator,
    syncStatus: StateFlow<SyncStatus>,
    private val onSyncNow: () -> Unit,
    private val onLinkConfirmed: suspend (String) -> Boolean,
    private val onLinkUnlinked: () -> Unit,
) : ViewModel() {

    private val submitting = MutableStateFlow(false)
    private val linking = MutableStateFlow(false)
    private val error = MutableStateFlow<AccountError?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val linkState = MutableStateFlow<LinkState>(LinkState.LocalOnly)
    private var linkJob: Job? = null

    init {
        var lastIdentity = (auth.state.value as? AuthState.SignedIn)?.session?.userId
        viewModelScope.launch {
            auth.state.collect { state ->
                val nextIdentity = (state as? AuthState.SignedIn)?.session?.userId
                if (nextIdentity != lastIdentity) {
                    lastIdentity = nextIdentity
                    linkJob?.cancel()
                    linking.value = false
                    linkState.value = LinkState.LocalOnly
                    error.value = null
                    errorMessage.value = null
                }
            }
        }
    }

    private val baseState: StateFlow<AccountUiState> = combine(
        auth.state,
        submitting,
        linking,
        error,
        linkState,
    ) { session, isSubmitting, isLinking, currentError, linkState ->
        AccountUiState(
            signedIn = session is AuthState.SignedIn,
            email = session.emailOrNull(),
            submitting = isSubmitting,
            linking = isLinking,
            awaitingVerificationEmail = (session as? AuthState.AwaitingVerification)?.email,
            error = currentError,
            linkState = linkState,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AccountUiState())

    /** Only a status owned by the signed-in user is shown; a stale identity never leaks. */
    private val accountScopedStatus = combine(auth.state, syncStatus) { session, status ->
        val identity = (session as? AuthState.SignedIn)?.session?.userId
        if (identity == null || status.identity != identity) null else status.toView()
    }

    val state: StateFlow<AccountUiState> = combine(baseState, accountScopedStatus, errorMessage) { base, status, message ->
        base.copy(syncStatus = status, errorMessage = message)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AccountUiState())

    fun syncNow() = onSyncNow()

    fun submitSignIn(email: String, password: String) = submit { auth.signIn(email, password) }

    fun submitSignUp(email: String, password: String) = submit { auth.signUp(email, password) }

    fun requestLink() {
        val session = auth.state.value as? AuthState.SignedIn ?: return
        if (linkState.value !is LinkState.LocalOnly || linking.value) return
        linkJob = viewModelScope.launch {
            linking.value = true
            error.value = null
            errorMessage.value = null
            try {
                val summary = link.preparePreview(session.session.userId)
                if ((auth.state.value as? AuthState.SignedIn)?.session?.userId == session.session.userId) {
                    linkState.value = LinkState.Preview(summary)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                error.value = if (cause.message.isNullOrBlank()) AccountError.UNKNOWN else AccountError.NETWORK
                linkState.value = LinkState.LocalOnly
            } finally {
                linking.value = false
                linkJob = null
            }
        }
    }

    fun confirmLink() {
        val session = auth.state.value as? AuthState.SignedIn ?: return
        val preview = linkState.value as? LinkState.Preview ?: return
        if (linking.value) return
        linkJob = viewModelScope.launch {
            linking.value = true
            error.value = null
            errorMessage.value = null
            try {
                when (val outcome = link.confirm(session.session.userId, preview.summary.plan) {
                    (auth.state.value as? AuthState.SignedIn)?.session?.userId == session.session.userId
                }) {
                    is LinkOutcome.Linked -> {
                        if ((auth.state.value as? AuthState.SignedIn)?.session?.userId == session.session.userId && onLinkConfirmed(session.session.userId)) {
                            linkState.value = LinkState.Linked(session.session.userId)
                        } else {
                            linkState.value = LinkState.LocalOnly
                            error.value = AccountError.LINK_PARTIAL
                            errorMessage.value = null
                        }
                    }
                    LinkOutcome.Blocked -> error.value = AccountError.LINK_BLOCKED
                    LinkOutcome.LocalChanged -> {
                        linkState.value = LinkState.LocalOnly
                        error.value = AccountError.LINK_STALE
                    }
                    is LinkOutcome.Partial -> {
                        linkState.value = LinkState.LocalOnly
                        error.value = AccountError.LINK_PARTIAL
                        val rejected = outcome.outcomes.filterIsInstance<app.tcg.app.data.sync.PushOutcome.Conflict>()
                        val details = rejected.mapNotNull { conflict -> conflict.opId?.let { "$it: ${conflict.reason}" } }
                        errorMessage.value = buildString {
                            append("A nuvem recebeu parte das operações e o vínculo local não foi concluído.")
                            if (details.isNotEmpty()) append(" Operações recusadas: ${details.joinToString()}. ")
                            append("Gere uma nova prévia e confira os conflitos antes de tentar novamente.")
                        }
                    }
                    is LinkOutcome.Refused -> {
                        linkState.value = LinkState.LocalOnly
                        error.value = AccountError.LINK_STALE
                    }
                    is LinkOutcome.Failed -> {
                        error.value = AccountError.NETWORK
                        errorMessage.value = outcome.message
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = AccountError.NETWORK
                errorMessage.value = null
            } finally {
                linking.value = false
                linkJob = null
            }
        }
    }

    fun cancelLink() {
        if (linkState.value is LinkState.Preview) {
            linkState.value = LinkState.LocalOnly
            error.value = null
            errorMessage.value = null
        }
    }

    fun signOut() {
        linkJob?.cancel()
        linkState.value = LinkState.LocalOnly
        linking.value = false
        onLinkUnlinked()
        viewModelScope.launch {
            auth.signOut()
            error.value = null
            errorMessage.value = null
        }
    }

    fun dismissError() {
        error.value = null
        errorMessage.value = null
    }

    private fun submit(block: suspend () -> AuthResult) {
        viewModelScope.launch {
            submitting.value = true
            error.value = null
            errorMessage.value = null
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


private fun SyncStatus.toView(): SyncStatusView {
    if (running) return SyncStatusView.Running
    val outcome = result ?: return SyncStatusView.Synced(pending = 0)
    return when (outcome) {
        is SyncResult.Complete -> SyncStatusView.Synced(outcome.pending)
        is SyncResult.Conflicts -> SyncStatusView.Conflicts(outcome.count, outcome.pending, outcome.details)
        is SyncResult.Offline -> SyncStatusView.Offline(outcome.pending)
        is SyncResult.Error -> SyncStatusView.Failed(outcome.pending)
    }
}
