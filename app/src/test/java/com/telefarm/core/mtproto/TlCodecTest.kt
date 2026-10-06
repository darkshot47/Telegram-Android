package com.telefarm.core.mtproto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The serializer of the session import request.
 *
 * The reference value below was produced by a reference MTProto implementation while serializing
 * exactly these arguments, so it checks the length encoding, the padding rule and the order of the
 * fields of `initConnection` and `invokeWithLayer` in one go.
 */
class TlCodecTest {

    @Test
    fun `the wrapped request matches the reference serialization`() {
        val token = ByteArray(TOKEN_SIZE) { it.toByte() }
        val query = TelegramApi.invokeWithLayer(
            layer = 229,
            query = TelegramApi.initConnection(
                apiId = 12345,
                deviceModel = "Android",
                systemVersion = "14",
                appVersion = "1.0.0",
                systemLanguageCode = "en",
                languagePack = "",
                languageCode = "en",
                query = TelegramApi.acceptLoginToken(token)
            )
        )
        assertEquals(REFERENCE_WRAPPED, query.toHex())
    }

    @Test
    fun `the login token is written with a one byte length`() {
        val body = TelegramApi.acceptLoginToken(ByteArray(TOKEN_SIZE) { it.toByte() })
        assertEquals(REFERENCE_ACCEPT, body.toHex())
    }

    @Test
    fun `byte strings round trip through every length class`() {
        for (size in intArrayOf(0, 1, 3, 4, 31, 32, 253, 254, 255, 1000)) {
            val value = ByteArray(size) { index -> ((index * 7 + 1) % 256).toByte() }
            val written = MtProtoBytes.writeBytes(value)

            assertEquals("size $size", MtProtoBytes.bytesSize(size), written.size)
            assertArrayEquals("size $size", value, TlReader(written).readBytes())
        }
    }

    @Test
    fun `the reader stops after the bytes it read`() {
        val value = "session".toByteArray()
        val written = MtProtoBytes.concat(MtProtoBytes.writeBytes(value), MtProtoBytes.writeInt(7))
        val reader = TlReader(written)

        assertArrayEquals(value, reader.readBytes())
        assertEquals(7, reader.readInt())
        assertEquals(0, reader.remaining)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TOKEN_SIZE = 32

        /** `auth.acceptLoginToken` with the token `00 01 .. 1f`. */
        const val REFERENCE_ACCEPT =
            "4dad94e820000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f000000"

        /** The same call wrapped into `initConnection` and `invokeWithLayer`, layer 229. */
        const val REFERENCE_WRAPPED =
            "0d0d9bdae5000000a95ecdc1000000003930000007416e64726f69640231340005312e302e30" +
                "000002656e000000000002656e004dad94e820000102030405060708090a0b0c0d0e0f1011" +
                "12131415161718191a1b1c1d1e1f000000"
    }
}
