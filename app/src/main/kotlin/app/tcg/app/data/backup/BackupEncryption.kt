package app.tcg.app.data.backup

import android.content.SharedPreferences
import android.util.Base64
import app.tcg.app.auth.SessionCipher
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface StoredBackupPassphrase {
    data object Disabled : StoredBackupPassphrase
    data object Unavailable : StoredBackupPassphrase
    data class Available(val value: CharArray) : StoredBackupPassphrase
}

interface BackupPassphraseStore {
    val enabled: Flow<Boolean>
    suspend fun read(): StoredBackupPassphrase
    suspend fun write(passphrase: CharArray?): Boolean
}

object NoBackupPassphraseStore : BackupPassphraseStore {
    override val enabled: Flow<Boolean> = flowOf(false)
    override suspend fun read(): StoredBackupPassphrase = StoredBackupPassphrase.Disabled
    override suspend fun write(passphrase: CharArray?): Boolean = passphrase == null
}

class AndroidBackupPassphraseStore(
    private val preferences: SharedPreferences,
    private val localCipher: SessionCipher,
) : BackupPassphraseStore {
    private val enabledState = MutableStateFlow(preferences.contains(PASSPHRASE_KEY))
    override val enabled: Flow<Boolean> = enabledState

    override suspend fun read(): StoredBackupPassphrase {
        val encoded = preferences.getString(PASSPHRASE_KEY, null) ?: return StoredBackupPassphrase.Disabled
        return try {
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
            val clear = localCipher.decrypt(encrypted)
            try {
                StoredBackupPassphrase.Available(String(clear, Charsets.UTF_8).toCharArray())
            } finally {
                clear.fill(0)
            }
        } catch (_: Exception) {
            StoredBackupPassphrase.Unavailable
        }
    }

    override suspend fun write(passphrase: CharArray?): Boolean {
        try {
            val editor = preferences.edit()
            val success = if (passphrase == null) {
                editor.remove(PASSPHRASE_KEY).commit()
            } else {
                val clear = String(passphrase).toByteArray(Charsets.UTF_8)
                val encrypted = try { localCipher.encrypt(clear) } finally { clear.fill(0) }
                editor.putString(PASSPHRASE_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()
            }
            if (success) enabledState.value = passphrase != null
            return success
        } catch (_: Exception) {
            return false
        } finally {
            passphrase?.fill('\u0000')
        }
    }

    private companion object {
        const val PASSPHRASE_KEY = "portable_backup_passphrase"
    }
}

/** Portable authenticated envelope. The passphrase is never included in the file. */
object BackupEncryption {
    private const val ENVELOPE_FORMAT = "tcg-colecao-encrypted-1"
    private const val KDF = "PBKDF2-HMAC-SHA256"
    private const val CIPHER = "AES-256-GCM"
    private const val ITERATIONS = 310_000
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 600_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    const val MAX_FILE_BYTES = 70 * 1024 * 1024
    private val json = Json
    private val random = SecureRandom()
    private val envelopePrefix = Regex("^\\s*\\{\\s*\"format\"\\s*:\\s*\"$ENVELOPE_FORMAT\"")

    fun isEncrypted(bytes: ByteArray): Boolean {
        if (bytes.size > MAX_FILE_BYTES) return false
        val prefixSize = minOf(bytes.size, 128)
        return envelopePrefix.containsMatchIn(String(bytes, 0, prefixSize, Charsets.UTF_8))
    }

    fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_FILE_BYTES) throw InvalidBackupException("O arquivo excede o limite de 70 MiB")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        try {
            validatePassphrase(passphrase)
            require(plaintext.size <= WebBackupAdapter.MAX_BYTES) { "O backup excede 50 MiB" }
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(ENVELOPE_FORMAT.toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(plaintext)
            val envelope = """{"format":"$ENVELOPE_FORMAT","kdf":"$KDF","iterations":$ITERATIONS,"salt":"${encode(salt)}","cipher":"$CIPHER","iv":"${encode(iv)}","ciphertext":"${encode(ciphertext)}"}"""
                .toByteArray(Charsets.UTF_8)
            require(envelope.size <= MAX_FILE_BYTES) { "O backup criptografado excede o limite" }
            return envelope
        } finally {
            passphrase.fill('\u0000')
        }
    }

    fun decrypt(envelope: ByteArray, passphrase: CharArray): ByteArray {
        try {
            require(envelope.size <= MAX_FILE_BYTES) { "O arquivo excede o limite de 70 MiB" }
            validatePassphrase(passphrase)
            val root = json.parseToJsonElement(envelope.toString(Charsets.UTF_8)) as? kotlinx.serialization.json.JsonObject
                ?: throw InvalidBackupException("Envelope criptografado inválido")
            fun string(name: String) = root[name]?.jsonPrimitive?.content
                ?: throw InvalidBackupException("Envelope criptografado inválido")
            val format = string("format")
            if (format != ENVELOPE_FORMAT || string("kdf") != KDF || string("cipher") != CIPHER ||
                root.keys != setOf("format", "kdf", "iterations", "salt", "cipher", "iv", "ciphertext")) {
                throw InvalidBackupException("Formato de backup criptografado não reconhecido")
            }
            val iterations = root["iterations"]?.jsonPrimitive?.intOrNull
                ?: throw InvalidBackupException("Parâmetros de criptografia inválidos")
            if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw InvalidBackupException("Parâmetros de criptografia inválidos")
            val salt = decode(string("salt"), SALT_BYTES)
            val iv = decode(string("iv"), IV_BYTES)
            val ciphertext = Base64.decode(string("ciphertext"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(ENVELOPE_FORMAT.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(ciphertext).also {
                if (it.size > WebBackupAdapter.MAX_BYTES) throw InvalidBackupException("O backup excede 50 MiB")
            }
        } catch (error: InvalidBackupException) {
            throw error
        } catch (_: AEADBadTagException) {
            throw InvalidBackupException("Senha incorreta ou arquivo criptografado corrompido")
        } catch (_: Exception) {
            throw InvalidBackupException("Arquivo criptografado inválido")
        } finally {
            passphrase.fill('\u0000')
        }
    }

    fun validatePassphrase(passphrase: CharArray) {
        require(passphrase.size in 12..256) { "Use uma senha de 12 a 256 caracteres" }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(keyBytes, "AES") } finally { keyBytes.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String, expectedSize: Int): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP).also {
            if (it.size != expectedSize) throw InvalidBackupException("Envelope criptografado inválido")
        }
    } catch (_: IllegalArgumentException) {
        throw InvalidBackupException("Envelope criptografado inválido")
    }
}
