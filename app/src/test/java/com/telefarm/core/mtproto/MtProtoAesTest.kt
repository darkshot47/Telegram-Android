package com.telefarm.core.mtproto

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * AES-IGE and AES-CTR against reference values.
 *
 * The vectors were produced with the reference MTProto implementation (Telethon 1.45.0, which
 * uses the same code paths as the official clients), so a match means this port encrypts byte
 * for byte the way Telegram expects.
 */
class MtProtoAesTest {

    @Test
    fun `ige encrypts the reference vector`() {
        val key = sequential(0, 32)
        val iv = sequential(0, 32)
        val plain = sequential(0, 64)
        val expected = hex(
            "e28112a53e5c89c7b1ea8071c133699f" +
                "d4e86b26c4bac9fc5af1ab8ce27a44a4" +
                "a9b2dabae5a422acbb4422404b5950cc" +
                "e880d70cef7a432da00b3a5fc79a7b35"
        )
        assertArrayEquals(expected, MtProtoAes.encryptIge(plain, key, iv))
    }

    @Test
    fun `ige decrypts the reference vector`() {
        val key = sequential(0, 32)
        val iv = sequential(0, 32)
        val cipher = hex(
            "e28112a53e5c89c7b1ea8071c133699f" +
                "d4e86b26c4bac9fc5af1ab8ce27a44a4" +
                "a9b2dabae5a422acbb4422404b5950cc" +
                "e880d70cef7a432da00b3a5fc79a7b35"
        )
        assertArrayEquals(sequential(0, 64), MtProtoAes.decryptIge(cipher, key, iv))
    }

    @Test
    fun `ige round trips data that does not repeat`() {
        val key = ByteArray(32) { ((it * 5 + 9) % 256).toByte() }
        val iv = ByteArray(32) { ((it * 13 + 7) % 256).toByte() }
        val plain = ByteArray(48) { ((it * it + 3) % 256).toByte() }
        val expected = hex(
            "a101561cf36508ab804ab2c0f0aa3478" +
                "e18eecc564ca22f9352f248d6cc9af50" +
                "a76726063a807f45245769a3db899adf"
        )
        assertArrayEquals(expected, MtProtoAes.encryptIge(plain, key, iv))
        assertArrayEquals(plain, MtProtoAes.decryptIge(expected, key, iv))
    }

    @Test
    fun `counter mode keeps running between chunks`() {
        val key = sequential(3, 16)
        val iv = sequential(9, 16)
        val data = sequential(0, 64)

        val oneShot = MtProtoAes.ctrUpdate(MtProtoAes.ctr(key, iv), data)

        val split = MtProtoAes.ctr(key, iv)
        val firstHalf = MtProtoAes.ctrUpdate(split, data.copyOfRange(0, 24))
        val secondHalf = MtProtoAes.ctrUpdate(split, data.copyOfRange(24, 64))

        assertArrayEquals(oneShot, firstHalf + secondHalf)
    }

    private fun sequential(start: Int, size: Int): ByteArray = ByteArray(size) { (start + it).toByte() }
}

/** Shared hex helper for the MTProto tests. */
internal fun hex(value: String): ByteArray {
    val cleaned = value.replace(" ", "").replace("\n", "")
    require(cleaned.length % 2 == 0) { "hex text must have an even length" }
    return ByteArray(cleaned.length / 2) { index ->
        cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
