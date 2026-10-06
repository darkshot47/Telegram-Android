package com.telefarm.core.mtproto

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Cipher

/**
 * Obfuscated abridged TCP transport ("obfuscated2") of MTProto.
 *
 * The connection starts with 64 random bytes. The first four bytes must not look like a
 * different protocol, the last eight are sent through an AES-CTR keystream whose key and IV are
 * derived from the header itself, and the transport tag of the abridged framing lives in the
 * middle of the header. Everything after the header is the same keystream, which is why the
 * outgoing counter keeps running while the incoming one starts at zero.
 *
 * Frames are abridged length prefixed: one byte while the body is shorter than 508 bytes, four
 * bytes otherwise, both counting 32 bit words.
 */
internal class MtProtoTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val random: (Int) -> ByteArray = { size -> ByteArray(size).also { SecureRandom().nextBytes(it) } }
) : Closeable {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var encryptor: Cipher? = null
    private var decryptor: Cipher? = null

    /** Opens the socket and performs the obfuscated handshake. */
    fun connect() {
        try {
            val connection = Socket()
            connection.tcpNoDelay = true
            connection.connect(InetSocketAddress(host, port), connectTimeoutMs)
            connection.soTimeout = readTimeoutMs
            socket = connection
            input = connection.getInputStream()
            output = connection.getOutputStream()
            writeHeader(connection)
        } catch (error: MtProtoException) {
            close()
            throw error
        } catch (error: Throwable) {
            close()
            throw MtProtoException.ConnectionFailed("could not connect to $host:$port", error)
        }
    }

    /** Sends one MTProto message. */
    fun send(message: ByteArray) {
        val stream = output ?: throw MtProtoException.ConnectionFailed("the connection is not open")
        val cipher = encryptor ?: throw MtProtoException.ConnectionFailed("the connection is not open")
        try {
            stream.write(frameHeader(message.size))
            stream.write(MtProtoAes.ctrUpdate(cipher, message))
            stream.flush()
        } catch (error: Throwable) {
            throw MtProtoException.ConnectionFailed("sending failed", error)
        }
    }

    /** Receives one MTProto message. */
    fun receive(): ByteArray {
        val stream = input ?: throw MtProtoException.ConnectionFailed("the connection is not open")
        val cipher = decryptor ?: throw MtProtoException.ConnectionFailed("the connection is not open")
        try {
            // The frame header travels through the same keystream as the payload, so it is read
            // through the running cipher before the length is known.
            val first = MtProtoAes.ctrUpdate(cipher, readFully(stream, 1))[0].toInt() and 0xFF
            val length = if (first < 127) {
                first
            } else {
                val extra = MtProtoAes.ctrUpdate(cipher, readFully(stream, 3))
                (extra[0].toInt() and 0xFF) or
                    ((extra[1].toInt() and 0xFF) shl 8) or
                    ((extra[2].toInt() and 0xFF) shl 16)
            }
            if (length <= 0) {
                throw MtProtoException.MalformedMessage("empty frame")
            }
            return MtProtoAes.ctrUpdate(cipher, readFully(stream, length shl 2))
        } catch (error: MtProtoException) {
            throw error
        } catch (error: java.net.SocketTimeoutException) {
            throw MtProtoException.Timeout("the data center did not answer in time")
        } catch (error: Throwable) {
            throw MtProtoException.ConnectionFailed("receiving failed", error)
        }
    }

    override fun close() {
        encryptor = null
        decryptor = null
        input = null
        output = null
        val connection = socket
        socket = null
        if (connection != null) {
            runCatching { connection.close() }
        }
    }

    private fun writeHeader(connection: Socket) {
        val header = newHeader()
        val reversed = ByteArray(48)
        for (index in 0 until 48) {
            reversed[index] = header[55 - index]
        }
        val outgoing = MtProtoAes.ctr(header.copyOfRange(8, 40), header.copyOfRange(40, 56))
        val incoming = MtProtoAes.ctr(reversed.copyOfRange(0, 32), reversed.copyOfRange(32, 48))
        encryptor = outgoing
        decryptor = incoming

        header[56] = ABRIDGED_TAG
        header[57] = ABRIDGED_TAG
        header[58] = ABRIDGED_TAG
        header[59] = ABRIDGED_TAG

        // The last eight bytes travel through the keystream that also encrypts the payload,
        // so the counter is already past the header when the first frame is written.
        val keystream = MtProtoAes.ctrUpdate(outgoing, header)
        System.arraycopy(keystream, 56, header, 56, 8)

        val stream = connection.getOutputStream()
        stream.write(header)
        stream.flush()
    }

    /** Random header that cannot be mistaken for another protocol. */
    private fun newHeader(): ByteArray {
        while (true) {
            val candidate = random(HEADER_SIZE)
            val firstByte = candidate[0].toInt() and 0xFF
            val firstWord = String(candidate, 0, 4, Charsets.ISO_8859_1)
            val fifthToEighthZero = (candidate[4].toInt() or candidate[5].toInt() or
                candidate[6].toInt() or candidate[7].toInt()) == 0
            if (firstByte == 0xEF || firstWord in FORBIDDEN_PREFIXES || fifthToEighthZero) {
                continue
            }
            return candidate
        }
    }

    private fun frameHeader(messageSize: Int): ByteArray {
        val words = messageSize shr 2
        return if (words < 127) {
            byteArrayOf(words.toByte())
        } else {
            byteArrayOf(0x7F, (words and 0xFF).toByte(), ((words shr 8) and 0xFF).toByte(), ((words shr 16) and 0xFF).toByte())
        }
    }

    private fun readFully(stream: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = stream.read(buffer, offset, size - offset)
            if (read < 0) {
                throw MtProtoException.ConnectionFailed("the connection was closed")
            }
            offset += read
        }
        return buffer
    }

    private companion object {
        const val HEADER_SIZE = 64
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 15_000
        const val ABRIDGED_TAG = 0xEF.toByte()
        val FORBIDDEN_PREFIXES = setOf("PVrG", "GET ", "POST", "\u00ee\u00ee\u00ee\u00ee")
    }
}
