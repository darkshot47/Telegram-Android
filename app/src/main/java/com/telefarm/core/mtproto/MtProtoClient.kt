package com.telefarm.core.mtproto

import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream

/** Describes the application to the data center; no credential is part of it. */
internal class MtProtoClientInfo(
    val apiId: Int,
    val deviceModel: String,
    val systemVersion: String,
    val appVersion: String,
    val systemLanguageCode: String = "en",
    val languageCode: String = "en"
)

/** Result of one remote procedure call. */
internal sealed interface MtProtoResponse {

    /** The call succeeded; [body] is the serialized result for callers that need it. */
    data class Success(val body: ByteArray) : MtProtoResponse

    /** The server rejected the call with a TL error. */
    data class Error(val code: Int, val message: String) : MtProtoResponse
}

/**
 * Minimal MTProto client that only sends `auth.acceptLoginToken`.
 *
 * This is the piece that turns an imported session into a real login: the data center is asked
 * to accept the login token that TDLib requested, signed with the authorization key of the
 * pasted session - which is exactly what another signed in client does when it scans a login
 * QR code. The implementation is deliberately small: one connection, one request, the handful of
 * service messages the server may answer with, and no secret ever written to a log.
 */
internal class MtProtoClient(
    private val clientInfo: MtProtoClientInfo,
    private val sessionId: Long = SecureRandom().nextLong(),
    private val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS
) {

    /**
     * Accepts the login token with the imported session.
     *
     * @throws MtProtoException when the data center cannot be reached, does not answer in time,
     * or answers with something that cannot be interpreted.
     */
    fun acceptLoginToken(session: ImportedSession, token: ByteArray): MtProtoResponse {
        val authorizationKey = MtProtoAuthorizationKey(session.authorizationKey)
        var lastError: MtProtoException? = null
        for (endpoint in endpoints(session)) {
            for (layer in TelegramApi.LAYERS) {
                val response: MtProtoResponse = try {
                    call(endpoint, authorizationKey, token, layer)
                } catch (error: MtProtoException) {
                    lastError = error
                    if (error is MtProtoException.ConnectionFailed || error is MtProtoException.Timeout) {
                        // Nothing is listening on this address; try the next one.
                        break
                    }
                    // The request itself was refused; an older layer may be accepted.
                    continue
                }
                if (response is MtProtoResponse.Error && isLayerProblem(response.message)) {
                    continue
                }
                return response
            }
        }
        throw lastError ?: MtProtoException.ConnectionFailed("no data center address could be used")
    }

    /** Addresses to try, most specific first: the session address, then the well known one. */
    private fun endpoints(session: ImportedSession): List<MtProtoEndpoint> {
        val result = LinkedHashSet<MtProtoEndpoint>()
        val known = TelegramDataCenter.address(session.dcId)
        val host = session.address ?: known?.host
        if (host != null) {
            result += MtProtoEndpoint(host, session.port ?: DEFAULT_PORT)
            result += MtProtoEndpoint(host, FALLBACK_PORT)
        }
        if (known != null) {
            result += MtProtoEndpoint(known.host, session.port ?: DEFAULT_PORT)
            result += MtProtoEndpoint(known.host, FALLBACK_PORT)
        }
        return result.toList()
    }

    private fun call(
        endpoint: MtProtoEndpoint,
        authorizationKey: MtProtoAuthorizationKey,
        token: ByteArray,
        layer: Int
    ): MtProtoResponse {
        val transport = MtProtoTransport(endpoint.host, endpoint.port)
        try {
            transport.connect()
            val state = MtProtoMessageState(authorizationKey, sessionId)
            val request = TelegramApi.invokeWithLayer(
                layer,
                TelegramApi.initConnection(
                    apiId = clientInfo.apiId,
                    deviceModel = clientInfo.deviceModel,
                    systemVersion = clientInfo.systemVersion,
                    appVersion = clientInfo.appVersion,
                    systemLanguageCode = clientInfo.systemLanguageCode,
                    languagePack = "",
                    languageCode = clientInfo.languageCode,
                    query = TelegramApi.acceptLoginToken(token)
                )
            )

            val deadline = System.currentTimeMillis() + totalTimeoutMs
            var pending: ByteArray? = request
            var sentMessageId = 0L
            while (System.currentTimeMillis() < deadline) {
                if (pending != null) {
                    val outgoing = state.createMessage(pending, contentRelated = true)
                    sentMessageId = outgoing.messageId
                    transport.send(outgoing.data)
                    pending = null
                }
                val incoming = state.decryptMessage(transport.receive())
                when (val outcome = handle(incoming, state)) {
                    is Handled.Answered ->
                        if (outcome.messageId == sentMessageId) {
                            return outcome.response
                        }

                    is Handled.Resend -> pending = request

                    Handled.Ignored -> Unit
                }
            }
            throw MtProtoException.Timeout("the data center did not answer in time")
        } finally {
            transport.close()
        }
    }

    /** Interprets one incoming message. */
    private fun handle(incoming: MtProtoIncoming, state: MtProtoMessageState): Handled {
        val body = incoming.body
        if (body.size < 4) {
            return Handled.Ignored
        }
        val reader = TlReader(body)
        return when (reader.peekConstructor()) {
            TelegramApi.CONSTRUCTOR_MSG_CONTAINER -> {
                reader.readInt()
                val count = reader.readInt()
                if (count < 0 || count > MAX_CONTAINER_SIZE) {
                    throw MtProtoException.MalformedMessage("invalid container")
                }
                var result: Handled = Handled.Ignored
                for (index in 0 until count) {
                    val messageId = reader.readLong()
                    reader.readInt() // sequence number
                    val length = reader.readInt()
                    if (length < 0 || length > reader.remaining) {
                        throw MtProtoException.MalformedMessage("invalid container entry")
                    }
                    val inner = reader.readRaw(length)
                    val handled = handle(MtProtoIncoming(messageId, 0, inner), state)
                    if (handled !is Handled.Ignored) {
                        result = handled
                    }
                }
                result
            }

            TelegramApi.CONSTRUCTOR_GZIP_PACKED -> {
                reader.readInt()
                val packed = reader.readBytes()
                handle(MtProtoIncoming(incoming.messageId, incoming.sequenceNumber, gunzip(packed)), state)
            }

            TelegramApi.CONSTRUCTOR_RPC_RESULT -> {
                reader.readInt()
                val requestMessageId = reader.readLong()
                if (reader.peekConstructor() == TelegramApi.CONSTRUCTOR_RPC_ERROR) {
                    reader.readInt()
                    val code = reader.readInt()
                    val message = reader.readString()
                    Handled.Answered(requestMessageId, MtProtoResponse.Error(code, message))
                } else {
                    Handled.Answered(requestMessageId, MtProtoResponse.Success(body.copyOfRange(RPC_RESULT_HEADER_SIZE, body.size)))
                }
            }

            TelegramApi.CONSTRUCTOR_NEW_SESSION_CREATED -> {
                reader.readInt()
                reader.readLong() // first message id
                reader.readLong() // unique id
                state.updateSalt(reader.readLong())
                Handled.Ignored
            }

            TelegramApi.CONSTRUCTOR_BAD_SERVER_SALT -> {
                reader.readInt()
                reader.readLong() // rejected message id
                reader.readInt() // rejected sequence number
                reader.readInt() // error code
                state.updateSalt(reader.readLong())
                Handled.Resend
            }

            TelegramApi.CONSTRUCTOR_BAD_MESSAGE -> {
                reader.readInt()
                reader.readLong()
                reader.readInt()
                val errorCode = reader.readInt()
                throw MtProtoException.BadMessage(errorCode, "the data center rejected the message ($errorCode)")
            }

            TelegramApi.CONSTRUCTOR_MSGS_ACK -> Handled.Ignored

            else -> Handled.Ignored
        }
    }

    private fun gunzip(packed: ByteArray): ByteArray = try {
        GZIPInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }
    } catch (error: Throwable) {
        throw MtProtoException.MalformedMessage("a compressed message could not be read")
    }

    /** Outcome of handling one message. */
    private sealed interface Handled {
        data class Answered(val messageId: Long, val response: MtProtoResponse) : Handled

        data object Resend : Handled

        data object Ignored : Handled
    }

    private companion object {
        const val DEFAULT_PORT = 443
        const val FALLBACK_PORT = 80
        const val MAX_CONTAINER_SIZE = 64
        const val DEFAULT_TOTAL_TIMEOUT_MS = 20_000L
        const val RPC_RESULT_HEADER_SIZE = 12

        /** The data center did not accept the layer the client claimed. */
        fun isLayerProblem(message: String): Boolean = message.contains("LAYER", ignoreCase = true)
    }
}
