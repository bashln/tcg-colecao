package app.tcg.app.data.sync

import app.tcg.app.auth.AuthSession
import app.tcg.app.auth.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolveSyncIdentityTest {

    private fun session(userId: String) = AuthSession(
        userId = userId,
        email = "$userId@example.com",
        accessToken = "at",
        refreshToken = "rt",
        expiresAtEpochSeconds = 5_000L,
    )

    @Test
    fun `signed in with a matching binding syncs under that identity`() {
        val auth = AuthState.SignedIn(session("user-a"))
        assertEquals("user-a", resolveSyncIdentity(auth, linkedIdentity = "user-a"))
    }

    @Test
    fun `signed out never syncs even with a stored binding`() {
        assertNull(resolveSyncIdentity(AuthState.SignedOut, linkedIdentity = "user-a"))
    }

    @Test
    fun `awaiting verification never syncs`() {
        val auth = AuthState.AwaitingVerification("a@example.com")
        assertNull(resolveSyncIdentity(auth, linkedIdentity = "user-a"))
    }

    @Test
    fun `a binding for a different account blocks sync entirely`() {
        val auth = AuthState.SignedIn(session("user-b"))
        assertNull("must never push under the previous identity", resolveSyncIdentity(auth, linkedIdentity = "user-a"))
    }

    @Test
    fun `signed in but never linked stays unbound until a preview is confirmed`() {
        val auth = AuthState.SignedIn(session("user-a"))
        assertNull(resolveSyncIdentity(auth, linkedIdentity = null))
    }
}
