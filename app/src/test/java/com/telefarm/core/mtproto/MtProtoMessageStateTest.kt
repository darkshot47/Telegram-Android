package com.telefarm.core.mtproto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message encryption against reference values.
 *
 * Both directions were produced with the reference MTProto implementation using the same
 * authorization key, session id, salt and message id, so any difference in the key derivation,
 * the padding or the framing shows up immediately.
 */
class MtProtoMessageStateTest {

    private val authorizationKey = MtProtoAuthorizationKey(REFERENCE_KEY)

    @Test
    fun `authorization key id is the little endian tail of the sha1 digest`() {
        // sha1(REFERENCE_KEY) = 0249b...; both values come from the reference implementation.
        assertEquals(3587517436832175774L, authorizationKey.keyId)
    }

    @Test
    fun `client message matches the reference vector`() {
        val state = state()
        val outgoing = state.createMessage(REFERENCE_BODY, contentRelated = true, messageId = CLIENT_MESSAGE_ID)

        assertEquals(CLIENT_MESSAGE_ID, outgoing.messageId)
        assertArrayEquals(REFERENCE_CLIENT_MESSAGE, outgoing.data)
    }

    @Test
    fun `server message is decrypted`() {
        val incoming = state().decryptMessage(REFERENCE_SERVER_MESSAGE)

        assertEquals(0x65E0C6A000000005L, incoming.messageId)
        assertEquals(1, incoming.sequenceNumber)
        assertArrayEquals(hex("0102030468656c6c6f2d6d7470726f746f"), incoming.body)
    }

    @Test
    fun `messages sent with another key are rejected`() {
        val otherKey = MtProtoAuthorizationKey(ByteArray(256) { ((it * 11 + 7) % 256).toByte() })
        val otherState = MtProtoMessageState(otherKey, sessionId = SESSION_ID, initialSalt = SALT)
        try {
            otherState.decryptMessage(REFERENCE_SERVER_MESSAGE)
            throw AssertionError("a message of another key must not be accepted")
        } catch (expected: MtProtoException.WrongAuthorizationKey) {
            // expected
        }
    }

    @Test
    fun `a corrupted message is rejected by its message key`() {
        val corrupted = REFERENCE_SERVER_MESSAGE.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 0x01).toByte()
        try {
            state().decryptMessage(corrupted)
            throw AssertionError("a corrupted message must not be accepted")
        } catch (expected: MtProtoException.BrokenMessageKey) {
            // expected
        }
    }

    @Test
    fun `message ids grow monotonically`() {
        val state = MtProtoMessageState(
            authorizationKey,
            sessionId = SESSION_ID,
            initialSalt = SALT,
            clock = { FIXED_TIME_MS }
        )
        val first = state.createMessage(REFERENCE_BODY, messageId = 0L)
        val second = state.createMessage(REFERENCE_BODY)
        assertTrue(second.messageId > first.messageId)
    }

    @Test
    fun `padding keeps the plaintext a multiple of the block size`() {
        val state = state()
        for (size in 0..40) {
            val outgoing = state.createMessage(ByteArray(size))
            // 8 bytes of key id, 16 bytes of message key and whole IGE blocks afterwards.
            assertEquals(0, (outgoing.data.size - 24) % MtProtoAes.BLOCK_SIZE)
        }
    }

    private fun state(): MtProtoMessageState = MtProtoMessageState(
        authorizationKey,
        sessionId = SESSION_ID,
        initialSalt = SALT,
        random = { size -> ByteArray(size) { index -> ((index * 11 + 5) % 256).toByte() } }
    )

    private companion object {
        const val SESSION_ID = 0x1122334455667788L
        const val SALT = 0x1234567890ABCDEFL
        const val CLIENT_MESSAGE_ID = 0x65E0C6A000000004L
        const val FIXED_TIME_MS = 1_700_000_000_000L

        /** `auth.acceptLoginToken` with a 32 byte token, as a serialized TL object. */
        val REFERENCE_BODY = hex(
            "4dad94e820000102030405060708090a0b0c0d0e0f101112131415161718191a" +
            "1b1c1d1e1f000000"
        )

        val REFERENCE_KEY = ByteArray(256) { ((it * 7 + 3) % 256).toByte() }

        /** The same key, session, salt and message id as in the reference implementation. */
        val REFERENCE_CLIENT_MESSAGE = hex(
            "9ed6e6ef196cc931bc1589e8ab611878cfd7d9fb370d2e196655f75f2899bef6" +
            "bf8391ff477df2dd4efe9a96e5800452321bf1d02f8387922703521016d4be7c" +
            "be4060788e8b7999203a0389a6456198e64654fad08d63715e76ba90ffd5f2ad" +
            "c8590428cc0a044a1d22a11b4cca333e8d9bef5b042077e5"
        )

        val REFERENCE_SERVER_MESSAGE = hex(
            "9ed6e6ef196cc9316801a62f07451eace4ea98753bfed2931feb1d49f849423d" +
            "32aba1e56f4abec6a8d630ee1f20074f354d665969d905961c61f2b7edc25333" +
            "e62c8ad54fefe6d50974ba974b97ed661777d202b92810f0"
        )
    }
}
