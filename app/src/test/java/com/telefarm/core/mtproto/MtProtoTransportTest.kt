package com.telefarm.core.mtproto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rejected openings of an obfuscated header.
 *
 * The first 64 bytes of the connection are random, but they must not look like another protocol:
 * a middlebox that reads an abridged tag, the start of an HTTP request or a header that is mostly
 * zero may close the connection, so such a header is generated again.
 */
class MtProtoTransportTest {

    private val transport = MtProtoTransport(HOST, PORT)

    @Test
    fun `accepts an ordinary header`() {
        assertFalse(transport.isForbiddenHeader(header()))
    }

    @Test
    fun `rejects a header that opens like the abridged transport`() {
        assertTrue(transport.isForbiddenHeader(header(0 to 0xEF.toByte())))
    }

    @Test
    fun `rejects a header that carries the intermediate tag`() {
        // The tag of the intermediate transport is 0xEE, and a repeated byte is what tells it
        // apart from a random opening.
        assertTrue(transport.isForbiddenHeader(header(0 to 0xEE.toByte(), 1 to 0xEE.toByte(),
            2 to 0xEE.toByte(), 3 to 0xEE.toByte())))
    }

    @Test
    fun `rejects a header that opens like an http request`() {
        assertTrue(transport.isForbiddenHeader(header("GET ")))
        assertTrue(transport.isForbiddenHeader(header("POST")))
        assertTrue(transport.isForbiddenHeader(header("PVrG")))
    }

    @Test
    fun `rejects a header whose fifth to eighth byte are zero`() {
        assertTrue(transport.isForbiddenHeader(header(4 to 0.toByte(), 5 to 0.toByte(),
            6 to 0.toByte(), 7 to 0.toByte())))
    }

    @Test
    fun `rejects a header that is shorter than the header`() {
        assertTrue(transport.isForbiddenHeader(ByteArray(HEADER_SIZE - 1) { 1 }))
    }

    @Test
    fun `accepts a header that only starts with the intermediate tag once`() {
        assertFalse(transport.isForbiddenHeader(header(0 to 0xEE.toByte(), 1 to 0x01.toByte())))
    }

    /** A header with an ordinary opening, with [changes] applied to it. */
    private fun header(vararg changes: Pair<Int, Byte>): ByteArray {
        val bytes = ByteArray(HEADER_SIZE) { index -> ((index * 7 + 1) and 0xFF).toByte() }
        changes.forEach { (index, value) -> bytes[index] = value }
        return bytes
    }

    /** The same, for a prefix that is written as text. */
    private fun header(prefix: String): ByteArray {
        val bytes = header()
        prefix.toByteArray(Charsets.US_ASCII).copyInto(bytes)
        return bytes
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 443
        const val HEADER_SIZE = 64
    }
}
