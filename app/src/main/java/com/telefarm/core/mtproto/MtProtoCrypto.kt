package com.telefarm.core.mtproto

import java.security.MessageDigest
import java.security.SecureRandom

/** Anything that prevents a message from being processed. */
internal sealed class MtProtoException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** The data center could not be reached, or the connection broke. */
    class ConnectionFailed(message: String, cause: Throwable? = null) :
        MtProtoException(message, cause)

    /** The data center did not answer in time. */
    class Timeout(message: String) : MtProtoException(message)

    /** The server sent something this implementation cannot parse. */
    class MalformedMessage(message: String) : MtProtoException(message)

    /** The answer is not encrypted with the expected authorization key. */
    class WrongAuthorizationKey(message: String) : MtProtoException(message)

    /** The message key does not match the payload, so the message must be discarded. */
    class BrokenMessageKey(message: String) : MtProtoException(message)

    /** The server reported a problem with the message itself, for example a wrong sequence. */
    class BadMessage(val errorCode: Int, message: String) : MtProtoException(message)
}

/**
 * A 2048 bit MTProto authorization key.
 *
 * The key never leaves this process in a readable form: it is only used for the IGE key
 * derivation and to compute the key identifier that is sent on the wire.
 */
internal class MtProtoAuthorizationKey(val key: ByteArray) {

    init {
        require(key.size == KEY_SIZE) { "MTProto authorization keys are $KEY_SIZE bytes" }
    }

    /** Identifier sent with every encrypted message: `sha1(key)[12..20]` read little endian. */
    val keyId: Long by lazy {
        val digest = MessageDigest.getInstance("SHA-1").digest(key)
        var identifier = 0L
        for (index in 0 until 8) {
            identifier = identifier or ((digest[12 + index].toLong() and 0xFF) shl (8 * index))
        }
        identifier
    }

    companion object {
        const val KEY_SIZE = 256
    }
}

/** One encrypted outgoing message together with the identifier it was sent under. */
internal class MtProtoOutgoing(
    val messageId: Long,
    val data: ByteArray
)

/** One decrypted incoming message, without its transport envelope. */
internal class MtProtoIncoming(
    val messageId: Long,
    val sequenceNumber: Int,
    val body: ByteArray
)

/**
 * Message encryption of MTProto 2.0.
 *
 * Every message is `salt + session id + message id + sequence + length + body`, padded with
 * random bytes to a multiple of 16 and encrypted with AES-IGE. The IGE key is derived from the
 * authorization key and the message key, which itself is a SHA-256 of the authorization key and
 * the plaintext, so a corrupted message is detected before it is parsed.
 *
 * This class holds no Android dependency on purpose: the message layer is exercised by unit
 * tests against reference vectors.
 */
internal class MtProtoMessageState(
    private val authorizationKey: MtProtoAuthorizationKey,
    private val sessionId: Long,
    initialSalt: Long = 0L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: (Int) -> ByteArray = { size -> ByteArray(size).also { SecureRandom().nextBytes(it) } }
) {

    /** Current server salt; updated by `new_session_created` and `bad_server_salt`. */
    var salt: Long = initialSalt
        private set

    private var sequenceNumber = 0
    private var lastMessageId = 0L

    fun updateSalt(value: Long) {
        salt = value
    }

    /**
     * Packs [body] into an encrypted message and returns it with its message identifier.
     *
     * [messageId] exists so that reference vector tests can produce reproducible messages; the
     * normal callers let the message layer assign the next identifier itself.
     */
    fun createMessage(
        body: ByteArray,
        contentRelated: Boolean = true,
        messageId: Long = nextMessageId()
    ): MtProtoOutgoing {
        val sequence = if (contentRelated) sequenceNumber * 2 + 1 else sequenceNumber * 2
        if (contentRelated) {
            sequenceNumber++
        }

        val payload = ByteArray(HEADER_SIZE + body.size)
        MtProtoBytes.writeLong(payload, 0, messageId)
        MtProtoBytes.writeInt(payload, 8, sequence)
        MtProtoBytes.writeInt(payload, 12, body.size)
        System.arraycopy(body, 0, payload, HEADER_SIZE, body.size)

        return MtProtoOutgoing(messageId, encrypt(payload))
    }

    /**
     * Decrypts a message received from the data center.
     *
     * @throws MtProtoException.WrongAuthorizationKey when another key was used,
     * [MtProtoException.BrokenMessageKey] when the message key does not match, and
     * [MtProtoException.MalformedMessage] when the framing is inconsistent.
     */
    fun decryptMessage(data: ByteArray): MtProtoIncoming {
        if (data.size < MIN_ENCRYPTED_SIZE || (data.size - MESSAGE_KEY_END) % MtProtoAes.BLOCK_SIZE != 0) {
            throw MtProtoException.MalformedMessage("encrypted message has an invalid size")
        }
        val keyId = MtProtoBytes.readLong(data, 0)
        if (keyId != authorizationKey.keyId) {
            throw MtProtoException.WrongAuthorizationKey("the server used an unknown authorization key id")
        }

        val messageKey = data.copyOfRange(AUTHORIZATION_KEY_ID_SIZE, MESSAGE_KEY_END)
        val (aesKey, aesIv) = deriveKeys(authorizationKey.key, messageKey, client = false)
        val plainText = MtProtoAes.decryptIge(data.copyOfRange(MESSAGE_KEY_END, data.size), aesKey, aesIv)

        val expectedKey = MtProtoBytes
            .sha256(authorizationKey.key.copyOfRange(96, 128), plainText)
            .copyOfRange(8, 24)
        if (!expectedKey.contentEquals(messageKey)) {
            throw MtProtoException.BrokenMessageKey("the message key does not match the payload")
        }

        val messageId = MtProtoBytes.readLong(plainText, 16)
        val sequence = MtProtoBytes.readInt(plainText, 24)
        val length = MtProtoBytes.readInt(plainText, 28)
        if (length < 0 || PLAIN_HEADER_SIZE + length > plainText.size) {
            throw MtProtoException.MalformedMessage("the message length is out of bounds")
        }
        return MtProtoIncoming(
            messageId = messageId,
            sequenceNumber = sequence,
            body = plainText.copyOfRange(PLAIN_HEADER_SIZE, PLAIN_HEADER_SIZE + length)
        )
    }

    /**
     * `msg_key` is the middle 16 bytes of a SHA-256 over the authorization key and the
     * plaintext; the IGE key and IV are then two more hashes of the authorization key and
     * `msg_key`, with a different offset for each direction.
     */
    private fun deriveKeys(key: ByteArray, messageKey: ByteArray, client: Boolean): Pair<ByteArray, ByteArray> {
        val offset = if (client) 0 else 8
        val hashA = MtProtoBytes.sha256(messageKey, key.copyOfRange(offset, offset + 36))
        val hashB = MtProtoBytes.sha256(key.copyOfRange(offset + 40, offset + 76), messageKey)
        val aesKey = byteArrayOf(
            *hashA.copyOfRange(0, 8),
            *hashB.copyOfRange(8, 24),
            *hashA.copyOfRange(24, 32)
        )
        val aesIv = byteArrayOf(
            *hashB.copyOfRange(0, 8),
            *hashA.copyOfRange(8, 24),
            *hashB.copyOfRange(24, 32)
        )
        return aesKey to aesIv
    }

    private fun encrypt(payload: ByteArray): ByteArray {
        val plainSize = SALT_SIZE + SESSION_ID_SIZE + payload.size
        val paddingSize = 12 + (MtProtoAes.BLOCK_SIZE - ((plainSize + 12) % MtProtoAes.BLOCK_SIZE)) % MtProtoAes.BLOCK_SIZE
        val plainText = ByteArray(plainSize + paddingSize)
        MtProtoBytes.writeLong(plainText, 0, salt)
        MtProtoBytes.writeLong(plainText, 8, sessionId)
        System.arraycopy(payload, 0, plainText, SALT_SIZE + SESSION_ID_SIZE, payload.size)

        val padding = random(paddingSize)
        System.arraycopy(padding, 0, plainText, plainSize, minOf(paddingSize, padding.size))

        val messageKey = MtProtoBytes
            .sha256(
                authorizationKey.key.copyOfRange(88, 120),
                plainText
            )
            .copyOfRange(8, 24)
        val (aesKey, aesIv) = deriveKeys(authorizationKey.key, messageKey, client = true)
        val encrypted = MtProtoAes.encryptIge(plainText, aesKey, aesIv)
        return MtProtoBytes.concat(MtProtoBytes.writeLong(authorizationKey.keyId), messageKey, encrypted)
    }

    /** `msg_id` is a timestamp with nanosecond precision; it has to grow monotonically. */
    private fun nextMessageId(): Long {
        val now = clock()
        val seconds = now / 1000
        val nanoseconds = (now % 1000) * 1_000_000
        var messageId = (seconds shl 32) or (nanoseconds shl 2)
        if (messageId <= lastMessageId) {
            messageId = lastMessageId + 4
        }
        lastMessageId = messageId
        return messageId
    }

    internal companion object {
        const val AUTHORIZATION_KEY_ID_SIZE = 8
        const val MESSAGE_KEY_END = 24
        const val SALT_SIZE = 8
        const val SESSION_ID_SIZE = 8
        const val HEADER_SIZE = 16
        const val PLAIN_HEADER_SIZE = 32
        const val MIN_ENCRYPTED_SIZE = MESSAGE_KEY_END + MtProtoAes.BLOCK_SIZE
    }
}
