package app.tcg.app.auth

import android.content.SharedPreferences
import android.util.Base64
import kotlinx.serialization.json.Json

/**
 * [SessionStore] that persists the session as ciphertext in app-private [SharedPreferences].
 * The token is serialized, authenticated-encrypted via [SessionCipher], then base64-encoded —
 * the raw preference value is never readable as plaintext. A value that can no longer be
 * decrypted (corruption, restored backup, key loss) is dropped so the next start is a clean
 * signed-out state instead of a crash or a stale token.
 */
class EncryptedSessionStore(
    private val prefs: SharedPreferences,
    private val cipher: SessionCipher,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SessionStore {

    override suspend fun read(): AuthSession? {
        val encoded = prefs.getString(PREF_KEY, null) ?: return null
        val decrypted = try {
            String(cipher.decrypt(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            clear()
            return null
        }
        return try {
            json.decodeFromString(AuthSession.serializer(), decrypted)
        } catch (_: Exception) {
            clear()
            null
        }
    }

    override suspend fun write(session: AuthSession) {
        val plaintext = json.encodeToString(AuthSession.serializer(), session).toByteArray(Charsets.UTF_8)
        val encoded = Base64.encodeToString(cipher.encrypt(plaintext), Base64.NO_WRAP)
        prefs.edit().putString(PREF_KEY, encoded).apply()
    }

    override suspend fun clear() {
        prefs.edit().remove(PREF_KEY).apply()
    }

    companion object {
        const val PREF_KEY = "sync_session"
    }
}
