package com.telefarm.core.mtproto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES primitives that MTProto needs.
 *
 * Two modes are required: IGE for encrypted messages and CTR for the obfuscated transport
 * handshake. Both are built on the platform AES implementation, so no third party
 * cryptography is bundled with the application.
 */
internal object MtProtoAes {

    /** AES block size in bytes. */
    const val BLOCK_SIZE = 16

    /** IGE uses a 32 byte initialization vector: one block per direction. */
    const val IGE_IV_SIZE = 32

    private const val ALGORITHM = "AES"
    private const val ECB_TRANSFORMATION = "AES/ECB/NoPadding"
    private const val CTR_TRANSFORMATION = "AES/CTR/NoPadding"

    /**
     * Encrypts full blocks with AES-IGE:
     *
     * `C_i = E(P_i xor C_(i-1)) xor P_(i-1)`
     *
     * where `C_-1` is the first half of [iv] and `P_-1` the second half.
     */
    fun encryptIge(plainText: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(plainText.size % BLOCK_SIZE == 0) { "AES-IGE needs whole blocks" }
        require(iv.size == IGE_IV_SIZE) { "AES-IGE needs a 32 byte IV" }
        val cipher = Cipher.getInstance(ECB_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, ALGORITHM))
        val result = ByteArray(plainText.size)
        var previousCipher = iv.copyOfRange(0, BLOCK_SIZE)
        var previousPlain = iv.copyOfRange(BLOCK_SIZE, IGE_IV_SIZE)
        var offset = 0
        while (offset < plainText.size) {
            val mixed = xorBlock(plainText, offset, previousCipher)
            val encrypted = cipher.doFinal(mixed)
            val block = xorBlock(encrypted, 0, previousPlain)
            System.arraycopy(block, 0, result, offset, BLOCK_SIZE)
            previousCipher = block
            previousPlain = plainText.copyOfRange(offset, offset + BLOCK_SIZE)
            offset += BLOCK_SIZE
        }
        return result
    }

    /**
     * Inverse of [encryptIge]: `P_i = D(C_i xor P_(i-1)) xor C_(i-1)`.
     */
    fun decryptIge(cipherText: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(cipherText.size % BLOCK_SIZE == 0) { "AES-IGE needs whole blocks" }
        require(iv.size == IGE_IV_SIZE) { "AES-IGE needs a 32 byte IV" }
        val cipher = Cipher.getInstance(ECB_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, ALGORITHM))
        val result = ByteArray(cipherText.size)
        var previousCipher = iv.copyOfRange(0, BLOCK_SIZE)
        var previousPlain = iv.copyOfRange(BLOCK_SIZE, IGE_IV_SIZE)
        var offset = 0
        while (offset < cipherText.size) {
            val current = cipherText.copyOfRange(offset, offset + BLOCK_SIZE)
            val mixed = xorBlock(current, 0, previousPlain)
            val decrypted = cipher.doFinal(mixed)
            val block = xorBlock(decrypted, 0, previousCipher)
            System.arraycopy(block, 0, result, offset, BLOCK_SIZE)
            previousCipher = current
            previousPlain = block
            offset += BLOCK_SIZE
        }
        return result
    }

    /**
     * Creates a continuous AES-CTR keystream.
     *
     * The same [Cipher] instance has to be reused for every chunk, exactly like OpenSSL's
     * counter mode does: the keystream position is part of the stream, not of the call.
     */
    fun ctr(key: ByteArray, iv: ByteArray): Cipher {
        require(iv.size == BLOCK_SIZE) { "AES-CTR needs a 16 byte IV" }
        val cipher = Cipher.getInstance(CTR_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, ALGORITHM), IvParameterSpec(iv))
        return cipher
    }

    /** Runs one CTR chunk through the running keystream. */
    fun ctrUpdate(cipher: Cipher, data: ByteArray): ByteArray = cipher.update(data) ?: ByteArray(0)

    /** XORs the 16 byte block of [source] at [offset] with [other]. */
    private fun xorBlock(source: ByteArray, offset: Int, other: ByteArray): ByteArray {
        val result = ByteArray(BLOCK_SIZE)
        for (index in 0 until BLOCK_SIZE) {
            result[index] = (source[offset + index].toInt() xor other[index].toInt()).toByte()
        }
        return result
    }
}
