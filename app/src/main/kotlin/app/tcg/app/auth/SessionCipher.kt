package app.tcg.app.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated-encryption boundary for session bytes. Production uses the Android Keystore;
 * tests inject a deterministic cipher. Keeping it an interface means the storage logic is
 * testable without a hardware-backed key.
 */
interface SessionCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable key held in the Android Keystore. The output blob is
 * self-contained: a 4-byte big-endian IV length, the IV, then the ciphertext. The key never
 * leaves secure hardware, so the stored value is unreadable without the device unlock.
 */
class AndroidKeystoreSessionCipher(
    private val keyAlias: String = KEY_ALIAS,
    private val keyStore: KeyStore = defaultKeyStore(),
) : SessionCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(IV_LENGTH_FIELD + iv.size + ciphertext.size)
            .putInt(iv.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(ciphertext)
        val ivLength = buffer.int
        if (ivLength !in 1..MAX_IV_LENGTH) throw IllegalArgumentException("IV inválida")
        val iv = ByteArray(ivLength)
        buffer.get(iv)
        val payload = ByteArray(buffer.remaining())
        buffer.get(payload)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(payload)
    }

    private fun obtainKey(): SecretKey {
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALIAS = "tcg_sync_session_key"
        private const val KEY_SIZE_BITS = 256
        private const val TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_FIELD = 4
        private const val MAX_IV_LENGTH = 64

        // A fresh random IV per encryption; kept out of the instance so it is easy to audit.
        internal fun secureRandom(): SecureRandom = SecureRandom()

        private fun defaultKeyStore(): KeyStore =
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }
}
