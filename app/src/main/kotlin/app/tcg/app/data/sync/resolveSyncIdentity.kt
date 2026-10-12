package app.tcg.app.data.sync

import app.tcg.app.auth.AuthState

/**
 * The one rule that decides which identity (if any) automatic sync may run under. Sync
 * is allowed only when a session is live AND this device's stored binding belongs to that
 * exact user — so a sign-out or an account switch can never push under the previous
 * identity. Anything else is unbound, which stops triggers without touching local data.
 */
fun resolveSyncIdentity(auth: AuthState, linkedIdentity: String?): String? =
    if (auth is AuthState.SignedIn && linkedIdentity != null && linkedIdentity == auth.session.userId) {
        linkedIdentity
    } else {
        null
    }
