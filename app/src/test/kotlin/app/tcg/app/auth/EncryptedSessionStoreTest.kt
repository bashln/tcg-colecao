package app.tcg.app.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EncryptedSessionStoreTest {

    /** Deterministic real transformation (not identity) so "not plaintext" assertions are meaningful. */
    private class XorCipher : SessionCipher {
        private val key = 0x5A

        override fun encrypt(plaintext: ByteArray): ByteArray =
            plaintext.map { (it.toInt() xor key).toByte() }.toByteArray()

        override fun decrypt(ciphertext: ByteArray): ByteArray = encrypt(ciphertext)
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var store: EncryptedSessionStore

    private val session = AuthSession(
        userId = "user-1",
        email = "a@example.com",
        accessToken = "super-secret-access-token",
        refreshToken = "super-secret-refresh-token",
        expiresAtEpochSeconds = 4_600L,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("session-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = EncryptedSessionStore(prefs, XorCipher())
    }

    @Test
    fun read_is_null_when_nothing_is_stored() = runTest {
        assertNull(store.read())
    }

    @Test
    fun write_then_read_round_trips_the_session() = runTest {
        store.write(session)
        assertEquals(session, store.read())
    }

    @Test
    fun persisted_value_is_never_plaintext() = runTest {
        store.write(session)

        val raw = prefs.getString("sync_session", null)
        assertNotNull(raw)
        assertFalse(raw!!.contains("super-secret-access-token"))
        assertFalse(raw.contains("super-secret-refresh-token"))
        assertFalse(raw.contains("a@example.com"))
    }

    @Test
    fun clear_removes_the_session() = runTest {
        store.write(session)
        store.clear()
        assertNull(store.read())
        assertNull(prefs.getString("sync_session", null))
    }

    @Test
    fun corrupted_ciphertext_signs_out_cleanly_without_crashing() = runTest {
        store.write(session)
        // Simulate on-disk corruption / a key we can no longer decrypt.
        prefs.edit().putString("sync_session", "!!not-base64-or-valid-ciphertext!!").commit()

        assertNull(store.read())
        // The unreadable value is dropped so the next start is a clean signed-out state.
        assertNull(prefs.getString("sync_session", null))
    }
}
