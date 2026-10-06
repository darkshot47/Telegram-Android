package com.telefarm.core.session

import java.util.Base64

/**
 * The login token of a `tg://login?token=...` link.
 *
 * Telegram hands this link to a client that wants to sign in on a new device (it is also the
 * content of the login QR code). Another client that is already signed in can approve it with the
 * token, which is exactly what the session login does with the key of a pasted session.
 */
object LoginToken {

    private const val LINK_PREFIX = "tg://login?token="

    /** True when [link] carries a login token. */
    fun matches(link: String): Boolean = link.trim().lowercase().startsWith(LINK_PREFIX)

    /**
     * Reads the token out of [link].
     *
     * The link is base64 encoded without padding, and clients differ in whether they use the
     * standard or the url safe alphabet, so both are accepted.
     *
     * @throws SessionStringException when the text is not a login link or carries no token.
     */
    fun fromLink(link: String): ByteArray {
        val trimmed = link.trim()
        if (!matches(trimmed)) {
            throw SessionStringException(SessionStringReason.UNKNOWN_FORMAT)
        }
        val encoded = trimmed.substring(LINK_PREFIX.length).trim()
        if (encoded.isEmpty()) {
            throw SessionStringException(SessionStringReason.EMPTY)
        }
        val normalized = encoded
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
        if (normalized.any { !it.isLetterOrDigit() && it != '-' && it != '_' }) {
            throw SessionStringException(SessionStringReason.NOT_BASE64)
        }
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val token = try {
            Base64.getUrlDecoder().decode(padded)
        } catch (error: IllegalArgumentException) {
            throw SessionStringException(SessionStringReason.NOT_BASE64)
        }
        if (token.isEmpty()) {
            throw SessionStringException(SessionStringReason.EMPTY)
        }
        return token
    }
}

/** Raised when the data center refuses the login token that a session tried to approve. */
class SessionLoginException(val code: Int, override val message: String) : Exception("$code: $message")
