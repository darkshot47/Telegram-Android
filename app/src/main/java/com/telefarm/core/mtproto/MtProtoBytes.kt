package com.telefarm.core.mtproto

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Little endian helpers shared by the MTProto message layer and the TL serializer.
 *
 * MTProto serializes every integer least significant byte first and pads byte strings to a
 * four byte boundary.
 */
internal object MtProtoBytes {

    /** Alignment of byte strings in the TL serialization. */
    const val ALIGNMENT = 4

    fun readInt(data: ByteArray, offset: Int): Int {
        require(offset + 4 <= data.size) { "not enough bytes for an int" }
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun readLong(data: ByteArray, offset: Int): Long {
        val low = readInt(data, offset).toLong() and 0xFFFFFFFFL
        val high = readInt(data, offset + 4).toLong() and 0xFFFFFFFFL
        return low or (high shl 32)
    }

    fun writeInt(value: Int): ByteArray = ByteArray(4).also { writeInt(it, 0, value) }

    fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    fun writeLong(value: Long): ByteArray = ByteArray(8).also { writeLong(it, 0, value) }

    fun writeLong(target: ByteArray, offset: Int, value: Long) {
        writeInt(target, offset, value.toInt())
        writeInt(target, offset + 4, (value ushr 32).toInt())
    }

    /**
     * Serializes a byte string the way the Telegram type language does.
     *
     * The length takes one byte while it fits into 253, otherwise the marker `0xFE` is followed by
     * three little endian length bytes. The padding aligns the length field *and* the content to a
     * four byte boundary, so a 32 byte token is followed by three zero bytes.
     */
    fun writeBytes(value: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(value.size + 8)
        writeLength(output, value.size)
        output.write(value)
        val padding = paddingFor(lengthSize(value.size), value.size)
        if (padding != 0) {
            output.write(ByteArray(padding))
        }
        return output.toByteArray()
    }

    fun writeString(value: String): ByteArray = writeBytes(value.toByteArray(Charsets.UTF_8))

    /** Reads a length prefixed byte string starting at [offset]. */
    fun readBytes(data: ByteArray, offset: Int): ByteArray {
        val headerSize = lengthSize(data, offset)
        val length = readLength(data, offset, headerSize)
        require(offset + headerSize + length <= data.size) { "malformed byte string" }
        return data.copyOfRange(offset + headerSize, offset + headerSize + length)
    }

    /** Size of a byte string, including its length field and its padding. */
    fun bytesSize(length: Int): Int {
        val headerSize = lengthSize(length)
        return headerSize + length + paddingFor(headerSize, length)
    }

    /** Number of bytes the length of [length] is written in. */
    fun lengthSize(length: Int): Int = if (length < SHORT_LENGTH_LIMIT) 1 else EXTENDED_LENGTH_SIZE

    /** Number of bytes the length at [offset] is written in. */
    fun lengthSize(data: ByteArray, offset: Int): Int {
        require(offset < data.size) { "not enough bytes for a length" }
        return if ((data[offset].toInt() and 0xFF) < SHORT_LENGTH_LIMIT) 1 else EXTENDED_LENGTH_SIZE
    }

    /** Reads a length at [offset] that was written in [headerSize] bytes. */
    fun readLength(data: ByteArray, offset: Int, headerSize: Int): Int {
        require(offset + headerSize <= data.size) { "not enough bytes for a length" }
        if (headerSize == 1) {
            return data[offset].toInt() and 0xFF
        }
        require((data[offset].toInt() and 0xFF) == SHORT_LENGTH_LIMIT) { "malformed length" }
        val length = (data[offset + 1].toInt() and 0xFF) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            ((data[offset + 3].toInt() and 0xFF) shl 16)
        require(length >= SHORT_LENGTH_LIMIT) { "malformed length" }
        return length
    }

    private fun writeLength(output: ByteArrayOutputStream, length: Int) {
        require(length >= 0 && length < MAX_LENGTH) { "a byte string of $length bytes cannot be written" }
        if (length < SHORT_LENGTH_LIMIT) {
            output.write(length)
        } else {
            output.write(SHORT_LENGTH_LIMIT)
            output.write(length and 0xFF)
            output.write((length shr 8) and 0xFF)
            output.write((length shr 16) and 0xFF)
        }
    }

    private fun paddingFor(headerSize: Int, length: Int): Int = (ALIGNMENT - (headerSize + length) % ALIGNMENT) % ALIGNMENT

    /** Lengths below this value are written in a single byte. */
    private const val SHORT_LENGTH_LIMIT = 254

    private const val EXTENDED_LENGTH_SIZE = 4

    private const val MAX_LENGTH = 1 shl 24

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val size = parts.sumOf { it.size }
        val result = ByteArray(size)
        var offset = 0
        parts.forEach {
            System.arraycopy(it, 0, result, offset, it.size)
            offset += it.size
        }
        return result
    }

    /** Hex form of random identifier material; used for diagnostics that carry no secrets. */
    fun toHex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }
}
