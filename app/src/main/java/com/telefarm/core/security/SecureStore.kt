package com.telefarm.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.telefarm.core.td.TelefarmLog
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted key/value store backed by the Android keystore.
 *
 * Used for the two things that must survive a restart without ever being readable: the TDLib
 * database encryption key and the account credentials. The payload is encrypted with an AES
 * key that never leaves the keystore, so a copy of the file alone is useless.
 *
 * Writes are atomic: the encrypted blob is written to a temporary file and then renamed.
 */
class SecureStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()
    private var values: MutableMap<String, String>? = null

    /** Reads a value, or null when it is absent or cannot be decrypted. */
    fun getString(key: String): String? = synchronized(lock) { map()[key] }

    /** Stores a value. An empty value removes the entry. */
    fun putString(key: String, value: String?) = synchronized(lock) {
        val current = map()
        if (value.isNullOrEmpty()) {
            if (current.remove(key) == null) return@synchronized
        } else if (current[key] == value) {
            return@synchronized
        } else {
            current[key] = value
        }
        persist(current)
    }

    fun remove(key: String) = putString(key, null)

    /** Removes everything; used when the user logs out or the data is invalid. */
    fun clear() = synchronized(lock) {
        values = mutableMapOf()
        persist(mutableMapOf())
    }

    private fun map(): MutableMap<String, String> = values ?: load().also { values = it }

    private fun load(): MutableMap<String, String> {
        if (!file.exists() || file.length() == 0L) return mutableMapOf()
        return try {
            val raw = Base64.decode(file.readBytes(), Base64.NO_WRAP)
            val plain = String(decrypt(raw), Charsets.UTF_8)
            val result = mutableMapOf<String, String>()
            plain.split(SEPARATOR).filter { it.isNotEmpty() }.forEach { entry ->
                val index = entry.indexOf('=')
                if (index > 0) {
                    val key = entry.substring(0, index)
                    val value = entry.substring(index + 1)
                    result[key] = String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
                }
            }
            result
        } catch (error: Throwable) {
            // A lost keystore key or a corrupted file must not crash the application: the
            // stored data is dropped and the user signs in again.
            TelefarmLog.e(TAG, "Stored data could not be decrypted", error)
            file.delete()
            mutableMapOf()
        }
    }

    private fun persist(current: Map<String, String>) {
        try {
            val plain = buildString {
                current.forEach { (key, value) ->
                    append(key)
                    append('=')
                    append(Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                    append(SEPARATOR)
                }
            }
            val encrypted = encrypt(plain.toByteArray(Charsets.UTF_8))
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeBytes(Base64.encode(encrypted, Base64.NO_WRAP))
            if (!temporary.renameTo(file)) {
                file.writeBytes(temporary.readBytes())
                temporary.delete()
            }
        } catch (error: Throwable) {
            TelefarmLog.e(TAG, "Stored data could not be saved", error)
        }
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plain)
        return cipher.iv + ciphertext
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val ciphertext = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { entry ->
            return entry.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "SecureStore"
        const val FILE_NAME = "secure-store.bin"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "telefarm.secure.store"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        const val SEPARATOR = '\n'
    }
}
