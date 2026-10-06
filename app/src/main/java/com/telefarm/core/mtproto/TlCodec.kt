package com.telefarm.core.mtproto

import java.io.ByteArrayOutputStream

/**
 * Writes the small subset of the Telegram type language that the session import needs.
 *
 * All integers are little endian and every byte string is padded to a four byte boundary,
 * exactly as described by the TL specification.
 */
internal class TlWriter(initialCapacity: Int = 64) {

    private val output = ByteArrayOutputStream(initialCapacity)

    fun writeInt(value: Int): TlWriter {
        output.write(MtProtoBytes.writeInt(value))
        return this
    }

    fun writeLong(value: Long): TlWriter {
        output.write(MtProtoBytes.writeLong(value))
        return this
    }

    /** Writes an already serialized object. */
    fun writeRaw(value: ByteArray): TlWriter {
        output.write(value)
        return this
    }

    /** Writes a length prefixed, padded byte string. */
    fun writeBytes(value: ByteArray): TlWriter {
        output.write(MtProtoBytes.writeBytes(value))
        return this
    }

    fun writeString(value: String): TlWriter = writeBytes(value.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = output.toByteArray()
}

/** Reads the answers of the data center. */
internal class TlReader(private val data: ByteArray, private var position: Int = 0) {

    val remaining: Int get() = data.size - position

    fun readInt(): Int {
        require(remaining >= 4) { "not enough bytes for an int" }
        val value = MtProtoBytes.readInt(data, position)
        position += 4
        return value
    }

    fun readLong(): Long {
        require(remaining >= 8) { "not enough bytes for a long" }
        val value = MtProtoBytes.readLong(data, position)
        position += 8
        return value
    }

    fun readBytes(): ByteArray {
        val headerSize = MtProtoBytes.lengthSize(data, position)
        val length = MtProtoBytes.readLength(data, position, headerSize)
        position += headerSize
        require(remaining >= length) { "not enough bytes for a byte string" }
        val value = data.copyOfRange(position, position + length)
        position += length + (MtProtoBytes.ALIGNMENT - (headerSize + length) % MtProtoBytes.ALIGNMENT) % MtProtoBytes.ALIGNMENT
        return value
    }

    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    /** Reads a length prefixed object and returns its raw bytes, constructor included. */
    fun readObject(): ByteArray = readBytes()

    /** Reads [count] bytes without any length prefix, as message containers store them. */
    fun readRaw(count: Int): ByteArray {
        require(count >= 0 && remaining >= count) { "not enough bytes to read" }
        val value = data.copyOfRange(position, position + count)
        position += count
        return value
    }

    /** Returns the constructor of the next object without consuming it. */
    fun peekConstructor(): Int {
        require(remaining >= 4) { "not enough bytes for a constructor" }
        return MtProtoBytes.readInt(data, position)
    }

    fun skip(count: Int) {
        require(count >= 0 && remaining >= count) { "not enough bytes to skip" }
        position += count
    }
}

/** Serialized requests of the Telegram API that the session import sends. */
internal object TelegramApi {

    val CONSTRUCTOR_ACCEPT_LOGIN_TOKEN = 0xE894AD4D.toInt()
    val CONSTRUCTOR_INIT_CONNECTION = 0xC1CD5EA9.toInt()
    val CONSTRUCTOR_INVOKE_WITH_LAYER = 0xDA9B0D0D.toInt()

    val CONSTRUCTOR_RPC_RESULT = 0xF35C6D01.toInt()
    val CONSTRUCTOR_RPC_ERROR = 0x2144CA19.toInt()
    val CONSTRUCTOR_MSG_CONTAINER = 0x73F1F8DC.toInt()
    val CONSTRUCTOR_GZIP_PACKED = 0x3072CFA1.toInt()
    val CONSTRUCTOR_NEW_SESSION_CREATED = 0x9EC20908.toInt()
    val CONSTRUCTOR_BAD_SERVER_SALT = 0xEDAB447B.toInt()
    val CONSTRUCTOR_BAD_MESSAGE = 0xA7EFF811.toInt()
    val CONSTRUCTOR_MSGS_ACK = 0x62D6B459.toInt()

    /**
     * Layers that are tried, newest first.
     *
     * `auth.acceptLoginToken` is defined long before the oldest entry; the layer only describes
     * how much of the API the client claims to understand. Trying more than one keeps the
     * session import working when a data center refuses the newest value.
     */
    val LAYERS = intArrayOf(229, 200, 180, 158)

    /** `auth.acceptLoginToken token:bytes = Authorization`. */
    fun acceptLoginToken(token: ByteArray): ByteArray = TlWriter(token.size + 8)
        .writeInt(CONSTRUCTOR_ACCEPT_LOGIN_TOKEN)
        .writeBytes(token)
        .toByteArray()

    /**
     * `invokeWithLayer layer:int query:!X = X`
     */
    fun invokeWithLayer(layer: Int, query: ByteArray): ByteArray = TlWriter(query.size + 8)
        .writeInt(CONSTRUCTOR_INVOKE_WITH_LAYER)
        .writeInt(layer)
        .writeRaw(query)
        .toByteArray()

    /**
     * `initConnection flags:# api_id:int device_model:string system_version:string
     * app_version:string system_lang_code:string lang_pack:string lang_code:string
     * proxy:flags.0?InputClientProxy params:flags.1?JSONValue query:!X = X`
     */
    fun initConnection(
        apiId: Int,
        deviceModel: String,
        systemVersion: String,
        appVersion: String,
        systemLanguageCode: String,
        languagePack: String,
        languageCode: String,
        query: ByteArray
    ): ByteArray = TlWriter(query.size + 128)
        .writeInt(CONSTRUCTOR_INIT_CONNECTION)
        .writeInt(0) // no proxy, no extra parameters
        .writeInt(apiId)
        .writeString(deviceModel)
        .writeString(systemVersion)
        .writeString(appVersion)
        .writeString(systemLanguageCode)
        .writeString(languagePack)
        .writeString(languageCode)
        .writeRaw(query)
        .toByteArray()
}
