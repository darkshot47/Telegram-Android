package com.telefarm.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(
    context: Context
) {

    companion object {
        private const val FILE_NAME = "telefarm_secure_store"
        private const val KEY_ALIAS = "telefarm_secure_key"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH = 128
        private const val SEPARATOR = '\n'
    }

    private val file: File =
        File(context.filesDir, FILE_NAME)

    private val key: SecretKey by lazy {
        loadOrCreateKey()
    }

    private val values: MutableMap<String, String> by lazy {
        load()
    }

    @Synchronized
    fun put(key: String, value: String) {
        values[key] = value
        save()
    }

    @Synchronized
    fun get(key: String): String? {
        return values[key]
    }

    @Synchronized
    fun remove(key: String) {
        if (values.remove(key) != null) {
            save()
        }
    }

    @Synchronized
    fun clear() {
        values.clear()
        if (file.exists()) {
            file.delete()
        }
    }

    @Synchronized
    fun contains(key: String): Boolean {
        return values.containsKey(key)
    }

    /**
     * Key used to encrypt the store.
     *
     * The key lives in the Android keystore, so it survives process restarts: without that the
     * store could be written but never read back. If the keystore is unavailable the store keeps
     * working with a key that only exists in memory.
     */
    private fun loadOrCreateKey(): SecretKey = try {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            existing
        } else {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build()
            )
            generator.generateKey()
        }
    } catch (_: Throwable) {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(KEY_SIZE_BITS)
        keyGenerator.generateKey()
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)

        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)

        return iv + encrypted
    }

    private fun decrypt(data: ByteArray): ByteArray {
        require(data.size > IV_LENGTH)

        val iv = data.copyOfRange(0, IV_LENGTH)
        val encrypted = data.copyOfRange(IV_LENGTH, data.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)

        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_LENGTH, iv)
        )

        return cipher.doFinal(encrypted)
    }

    private fun save() {
        try {
            val plainText = buildString {
                values.forEach { (key, value) ->
                    append(key)
                    append('=')
                    append(
                        Base64.encodeToString(
                            value.toByteArray(Charsets.UTF_8),
                            Base64.NO_WRAP
                        )
                    )
                    append(SEPARATOR)
                }
            }

            val encrypted = encrypt(
                plainText.toByteArray(Charsets.UTF_8)
            )

            val encoded = Base64.encodeToString(
                encrypted,
                Base64.NO_WRAP
            )

            file.writeText(
                encoded,
                Charsets.UTF_8
            )
        } catch (_: Throwable) {
            // Ignore persistence errors.
        }
    }

    private fun load(): MutableMap<String, String> {
        if (!file.exists() || file.length() == 0L) {
            return mutableMapOf()
        }

        return try {
            val raw = Base64.decode(
                file.readBytes(),
                Base64.NO_WRAP
            )

            // FIX: decrypt() returns ByteArray, so convert it to String first.
            val plain = String(
                decrypt(raw),
                Charsets.UTF_8
            )

            val result = mutableMapOf<String, String>()

            plain
                .split(SEPARATOR)
                .filter { it.isNotEmpty() }
                .forEach { entry ->
                    val index = entry.indexOf('=')

                    if (index > 0) {
                        val key = entry.substring(0, index)
                        val value = entry.substring(index + 1)

                        result[key] = String(
                            Base64.decode(
                                value,
                                Base64.NO_WRAP
                            ),
                            Charsets.UTF_8
                        )
                    }
                }

            result
        } catch (_: Throwable) {
            mutableMapOf()
        }
    }
}
