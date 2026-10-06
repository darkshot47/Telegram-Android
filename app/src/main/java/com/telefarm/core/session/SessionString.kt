package com.telefarm.core.session

import java.util.Base64

/** Which client wrote a session string. */
enum class SessionFormat {
    /** `StringSession` of Telethon and of Telegram clients built on the same layout. */
    TELETHON,

    /** `StringSession` of GramJS, which stores the address of the data center as text. */
    GRAM_JS,

    /** Session string of Hydrogram and of Pyrogram forks that export one. */
    HYDROGRAM,

    /** Older layout that stores the application id instead of the server address. */
    LEGACY,

    /** Prefixed with `telefarm:`, otherwise identical to the shared layout. */
    TELEFARM
}

/** Why a session string could not be used. */
enum class SessionStringReason {
    /** Nothing was pasted. */
    EMPTY,

    /** The text is not valid base64. */
    NOT_BASE64,

    /** The decoded data does not match any known session layout. */
    UNKNOWN_FORMAT,

    /** The session belongs to a data center that does not exist. */
    INVALID_DATA_CENTER,

    /** The authorization key is missing or filled with zeroes. */
    INVALID_KEY,

    /** The session was created in the Telegram test environment. */
    TEST_MODE
}

/** Raised when a pasted session string cannot be interpreted. No secret is part of the message. */
class SessionStringException(val reason: SessionStringReason) : Exception(reason.name)

/**
 * The parts of a session string that are needed to use it: the data center it belongs to and the
 * authorization key of the account.
 *
 * The key is a credential: it is never logged, never written to preferences in clear text and
 * never shown in the user interface.
 */
class ImportedSession(
    val dcId: Int,
    val authorizationKey: ByteArray,
    val address: String?,
    val port: Int?,
    val applicationId: Int?,
    val testMode: Boolean,
    val format: SessionFormat
) {

    /** Human readable summary of the session; carries no secret. */
    fun describe(): String {
        val endpoint = if (address != null) "$address:${port ?: DEFAULT_PORT}" else "data center $dcId"
        return "$endpoint · ${format.name.lowercase().replace('_', ' ')}"
    }

    private companion object {
        const val DEFAULT_PORT = 443
    }
}

/**
 * Reads the session string formats that are in use today, so a session can be moved into the
 * application without asking Telegram for a new login code.
 *
 * Supported layouts:
 *
 * * Telethon 1.23+ and GramJS: a leading `1`, then base64url of data center, address, port and
 *   the 256 byte authorization key.
 * * Hydrogram and Pyrogram forks: base64url of data center, application id, test flag, key,
 *   user id and bot flag.
 * * Older layout with data center, application id, key and test flag.
 * * The same shared layout with a `telefarm:` prefix.
 */
object SessionString {

    private const val VERSION_PREFIX = '1'
    private const val TELEFARM_PREFIX = "telefarm:"
    private const val DATA_CENTER_COUNT = 5
    private const val KEY_SIZE = 256
    private const val MIN_KEY_BYTES_SET = 16
    private const val PORT_MIN = 1
    private const val PORT_MAX = 65535

    /** Telethon layout: data center, IPv4 or IPv6 address, port, key. */
    private const val TELETHON_IPV4_SIZE = 1 + 4 + 2 + KEY_SIZE
    private const val TELETHON_IPV6_SIZE = 1 + 16 + 2 + KEY_SIZE

    /** GramJS layout: data center, length prefixed address, port, key. */
    private const val GRAM_JS_HEADER_SIZE = 1 + 2 + 2 + KEY_SIZE

    /** Hydrogram layout: data center, application id, test flag, key, user id, bot flag. */
    private const val HYDROGRAM_SIZE = 1 + 4 + 1 + KEY_SIZE + 8 + 1

    /** Older layout: data center, application id, key, test flag. */
    private const val LEGACY_SIZE = 1 + 4 + KEY_SIZE + 1

    /**
     * Parses a pasted session string.
     *
     * @throws SessionStringException when the text is empty, is not base64, or does not carry a
     * usable production session.
     */
    fun parse(raw: String): ImportedSession {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw SessionStringException(SessionStringReason.EMPTY)
        }

        val withoutWhitespace = trimmed.filterNot { it.isWhitespace() }
        val lowerCased = withoutWhitespace.lowercase()
        val hasTelefarmPrefix = lowerCased.startsWith(TELEFARM_PREFIX)
        val body = if (hasTelefarmPrefix) withoutWhitespace.substring(TELEFARM_PREFIX.length) else withoutWhitespace

        if (body.startsWith(VERSION_PREFIX)) {
            val decoded = decodeOrNull(body.substring(1))
            if (decoded != null) {
                sessionFromVersionedBody(decoded, hasTelefarmPrefix)?.let { return finish(it) }
            }
        }

        val decoded = decodeOrNull(body)
            ?: throw SessionStringException(SessionStringReason.NOT_BASE64)

        sessionFromVersionedBody(decoded, hasTelefarmPrefix)?.let { return finish(it) }
        sessionFromHydrogramBody(decoded)?.let { return finish(it) }
        sessionFromLegacyBody(decoded)?.let { return finish(it) }
        sessionFromGramJsBody(decoded)?.let { return finish(it) }
        throw SessionStringException(SessionStringReason.UNKNOWN_FORMAT)
    }

    /** Rejects sessions that cannot be used against the production data centers. */
    private fun finish(session: ImportedSession): ImportedSession {
        if (session.testMode) {
            throw SessionStringException(SessionStringReason.TEST_MODE)
        }
        return session
    }

    /**
     * Serializes a session with the shared Telethon layout, so the string can also be used by
     * other clients that understand it.
     */
    fun encode(session: ImportedSession): String {
        require(session.dcId in 1..DATA_CENTER_COUNT) { "unsupported data center" }
        require(session.authorizationKey.size == KEY_SIZE) { "unsupported key size" }
        val address = session.address ?: DEFAULT_ADDRESSES[session.dcId] ?: DEFAULT_ADDRESSES.getValue(2)
        val packed = ByteArray(TELETHON_IPV4_SIZE)
        packed[0] = session.dcId.toByte()
        val octets = address.split('.')
        require(octets.size == 4) { "only IPv4 addresses can be written" }
        octets.forEachIndexed { index, part -> packed[1 + index] = part.toInt().toByte() }
        val port = session.port ?: DEFAULT_PORT
        packed[5] = ((port shr 8) and 0xFF).toByte()
        packed[6] = (port and 0xFF).toByte()
        System.arraycopy(session.authorizationKey, 0, packed, 7, KEY_SIZE)
        return VERSION_PREFIX + Base64.getUrlEncoder().encodeToString(packed)
    }

    /** Telethon layout, optionally written by GramJS, which prefixes the address with its size. */
    private fun sessionFromVersionedBody(data: ByteArray, telefarmPrefix: Boolean): ImportedSession? {
        if (data.size == TELETHON_IPV4_SIZE) {
            return telethonSession(data, telefarmPrefix, addressSize = 4)
        }
        if (data.size == TELETHON_IPV6_SIZE) {
            // A GramJS session whose address text is 14 bytes long decodes to exactly the same
            // size as a Telethon session with an IPv6 address, so the address decides: the bytes of
            // a GramJS address are the characters of a host name, while an IPv6 address stored as
            // bytes contains bytes that cannot be printed.
            val gramJs = sessionFromGramJsBody(data)
            if (gramJs != null && isTextAddress(data, GRAM_JS_ADDRESS_FOR_IPV6_SIZE)) {
                return gramJs
            }
            return telethonSession(data, telefarmPrefix, addressSize = 16) ?: gramJs
        }
        return sessionFromGramJsBody(data)
    }

    private fun telethonSession(data: ByteArray, telefarmPrefix: Boolean, addressSize: Int): ImportedSession? {
        val dcId = data[0].toInt() and 0xFF
        if (!isValidDataCenter(dcId)) {
            return null
        }
        val address = formatAddress(data.copyOfRange(1, 1 + addressSize)) ?: return null
        val port = readShort(data, 1 + addressSize)
        val key = data.copyOfRange(3 + addressSize, 3 + addressSize + KEY_SIZE)
        if (!isUsableKey(key)) {
            return null
        }
        return ImportedSession(
            dcId = dcId,
            authorizationKey = key,
            address = address,
            port = port.takeIf { it in PORT_MIN..PORT_MAX },
            applicationId = null,
            testMode = false,
            format = if (telefarmPrefix) SessionFormat.TELEFARM else SessionFormat.TELETHON
        )
    }

    /** True when the address of a GramJS session of this size is a host name or an address. */
    private fun isTextAddress(data: ByteArray, addressSize: Int): Boolean {
        if (addressSize <= 0 || data.size < GRAM_JS_HEADER_SIZE + addressSize) {
            return false
        }
        return (0 until addressSize).all { index ->
            val value = data[3 + index].toInt() and 0xFF
            value in PRINTABLE_ASCII
        }
    }

    /** GramJS layout: data center, two byte address length, address, port, key. */
    private fun sessionFromGramJsBody(data: ByteArray): ImportedSession? {
        if (data.size < GRAM_JS_HEADER_SIZE + 1) {
            return null
        }
        val dcId = data[0].toInt() and 0xFF
        if (!isValidDataCenter(dcId)) {
            return null
        }
        val addressLength = readShort(data, 1)
        if (addressLength <= 0 || addressLength > 100 || data.size != GRAM_JS_HEADER_SIZE + addressLength) {
            return null
        }
        val addressBytes = data.copyOfRange(3, 3 + addressLength)
        val address = formatAddress(addressBytes) ?: return null
        val port = readShort(data, 3 + addressLength)
        val key = data.copyOfRange(5 + addressLength, 5 + addressLength + KEY_SIZE)
        if (!isUsableKey(key)) {
            return null
        }
        return ImportedSession(
            dcId = dcId,
            authorizationKey = key,
            address = address,
            port = port.takeIf { it in PORT_MIN..PORT_MAX },
            applicationId = null,
            testMode = false,
            format = SessionFormat.GRAM_JS
        )
    }

    /** Hydrogram layout: data center, application id, test flag, key, user id, bot flag. */
    private fun sessionFromHydrogramBody(data: ByteArray): ImportedSession? {
        if (data.size != HYDROGRAM_SIZE) {
            return null
        }
        val dcId = data[0].toInt() and 0xFF
        if (!isValidDataCenter(dcId)) {
            return null
        }
        val applicationId = readInt(data, 1)
        val testMode = data[5].toInt()
        val key = data.copyOfRange(6, 6 + KEY_SIZE)
        if (testMode !in 0..1 || !isUsableKey(key)) {
            return null
        }
        return ImportedSession(
            dcId = dcId,
            authorizationKey = key,
            address = null,
            port = null,
            applicationId = applicationId.takeIf { it > 0 },
            testMode = testMode == 1,
            format = SessionFormat.HYDROGRAM
        )
    }

    /** Older layout: data center, application id, key, test flag. */
    private fun sessionFromLegacyBody(data: ByteArray): ImportedSession? {
        if (data.size != LEGACY_SIZE) {
            return null
        }
        val dcId = data[0].toInt() and 0xFF
        if (!isValidDataCenter(dcId)) {
            return null
        }
        val applicationId = readInt(data, 1)
        val key = data.copyOfRange(5, 5 + KEY_SIZE)
        val testMode = data[LEGACY_SIZE - 1].toInt()
        if (testMode !in 0..1 || !isUsableKey(key)) {
            return null
        }
        return ImportedSession(
            dcId = dcId,
            authorizationKey = key,
            address = null,
            port = null,
            applicationId = applicationId.takeIf { it > 0 },
            testMode = testMode == 1,
            format = SessionFormat.LEGACY
        )
    }

    /** Base64url with or without padding, and with the standard alphabet tolerated. */
    private fun decodeOrNull(value: String): ByteArray? {
        if (value.isEmpty()) {
            return null
        }
        val normalized = value
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
        if (normalized.any { !it.isLetterOrDigit() && it != '-' && it != '_' }) {
            return null
        }
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return try {
            Base64.getUrlDecoder().decode(padded)
        } catch (error: IllegalArgumentException) {
            null
        }
    }

    /** IPv4 and IPv6 literals are formatted properly, host names are kept as they are. */
    private fun formatAddress(bytes: ByteArray): String? {
        if (bytes.isEmpty()) {
            return null
        }
        if (bytes.size == 4) {
            return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        }
        if (bytes.size == 16) {
            return formatIpv6(bytes)
        }
        val text = String(bytes, Charsets.US_ASCII)
        return text.takeIf { candidate ->
            candidate.isNotEmpty() && candidate.length <= 100 &&
                candidate.all { it.isLetterOrDigit() || it == '.' || it == '-' }
        }
    }

    private fun formatIpv6(bytes: ByteArray): String {
        val parts = ArrayList<String>(8)
        for (index in 0 until 8) {
            val value = ((bytes[index * 2].toInt() and 0xFF) shl 8) or (bytes[index * 2 + 1].toInt() and 0xFF)
            parts += value.toString(16)
        }
        return parts.joinToString(":")
    }

    private fun isValidDataCenter(dcId: Int): Boolean = dcId in 1..DATA_CENTER_COUNT

    private fun isUsableKey(key: ByteArray): Boolean =
        key.size == KEY_SIZE && key.count { it != ZERO_BYTE } >= MIN_KEY_BYTES_SET

    private fun readShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private const val DEFAULT_PORT = 443
    private const val ZERO_BYTE: Byte = 0

    /** Number of characters the address of a GramJS session of the IPv6 size has. */
    private const val GRAM_JS_ADDRESS_FOR_IPV6_SIZE = TELETHON_IPV6_SIZE - GRAM_JS_HEADER_SIZE

    private val PRINTABLE_ASCII = 0x21..0x7E

    private val DEFAULT_ADDRESSES = mapOf(
        1 to "149.154.175.53",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "91.108.56.130"
    )
}
