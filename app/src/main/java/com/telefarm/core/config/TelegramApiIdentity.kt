package com.telefarm.core.config

import com.telefarm.data.model.CredentialSource
import com.telefarm.data.model.TelegramCredentials

/** Outcome of checking a pair of credentials that somebody typed into the sign in screen. */
sealed interface CredentialInputResult {

    /** Both values are usable; [credentials] can be applied to the client. */
    data class Valid(val credentials: TelegramCredentials) : CredentialInputResult

    /** Nothing was typed: the built in credentials stay in use. */
    data object Empty : CredentialInputResult

    /** The id is not a positive number. */
    data object InvalidApiId : CredentialInputResult

    /** The hash is not the 32 character value that my.telegram.org hands out. */
    data object InvalidApiHash : CredentialInputResult
}

/**
 * Decides which Telegram application credentials the client signs in with.
 *
 * MTProto itself needs an application id on every connection: `initConnection` carries it, and
 * TDLib refuses to start without both values. A client therefore always has credentials - the
 * only question is whose they are. This object answers that question in a fixed order:
 *
 * 1. credentials the user of this device entered on the sign in screen,
 * 2. credentials compiled into the build (`TELEGRAM_API_ID` / `TELEGRAM_API_HASH`),
 * 3. the built in identity of the official Android client.
 *
 * Step 3 is what makes the application usable straight after installing it: the sign in screen
 * appears, a pasted session string is accepted, and a phone number login can be started without
 * anybody having registered an application first. It is a shared identity, so it is documented in
 * the README and the screen offers to replace it with a personal pair.
 */
object TelegramApiIdentity {

    /**
     * Application id of the official Telegram Android client.
     *
     * Published by the client itself and used by third party clients that want to look like the
     * platform they run on. Telegram's own terms ask every application to register a personal
     * pair; [CredentialSource.BUILT_IN] marks that this one is shared.
     */
    const val FALLBACK_API_ID = 6

    /** Application hash that belongs to [FALLBACK_API_ID]. */
    const val FALLBACK_API_HASH = "eb06d4abfb49dc3eeb1aeb98ae0f581e"

    /** Credentials the application uses when nothing else was provided. */
    val FALLBACK = TelegramCredentials(
        apiId = FALLBACK_API_ID,
        apiHash = FALLBACK_API_HASH,
        source = CredentialSource.BUILT_IN
    )

    /** Length of the hash that my.telegram.org generates. */
    private const val API_HASH_LENGTH = 32

    /** Upper bound of an application id; ids are handed out sequentially and stay small. */
    private const val API_ID_MAX = 999_999_999

    private val HEX_CHARACTERS = '0'..'f'

    /**
     * Picks the credentials to use.
     *
     * Every argument is optional: an empty or zero value means "not provided". The result is
     * never unconfigured, which is what keeps the sign in screen reachable on a fresh install.
     */
    fun resolve(
        userApiId: Int?,
        userApiHash: String?,
        buildApiId: Int,
        buildApiHash: String
    ): TelegramCredentials {
        userCredentials(userApiId, userApiHash)?.let { return it }
        buildCredentials(buildApiId, buildApiHash)?.let { return it }
        return FALLBACK
    }

    /**
     * Checks a pair that was typed into the sign in screen.
     *
     * Whitespace and the `api_id = ...` decoration that my.telegram.org shows are tolerated,
     * because that text is what people copy.
     */
    fun parseInput(rawApiId: String, rawApiHash: String): CredentialInputResult {
        val idText = rawApiId.trim()
        val hashText = rawApiHash.trim()
        if (idText.isEmpty() && hashText.isEmpty()) {
            return CredentialInputResult.Empty
        }
        val apiId = parseApiId(idText) ?: return CredentialInputResult.InvalidApiId
        val apiHash = normalizeApiHash(hashText) ?: return CredentialInputResult.InvalidApiHash
        return CredentialInputResult.Valid(
            TelegramCredentials(apiId, apiHash, CredentialSource.USER_PROVIDED)
        )
    }

    /** Reads an application id out of text; null when the text is not one. */
    fun parseApiId(raw: String): Int? {
        val digits = raw.filter { !it.isWhitespace() }
        if (digits.isEmpty() || digits.any { !it.isDigit() }) {
            return null
        }
        val value = digits.toLongOrNull() ?: return null
        if (value <= 0L || value > API_ID_MAX.toLong()) {
            return null
        }
        return value.toInt()
    }

    /** Lower cases and validates an application hash; null when it is not one. */
    fun normalizeApiHash(raw: String): String? {
        val value = raw.filter { !it.isWhitespace() }.lowercase()
        if (value.length != API_HASH_LENGTH) {
            return null
        }
        return value.takeIf { candidate -> candidate.all { it in HEX_CHARACTERS } }
    }

    /** True when both values belong together and can be sent to Telegram. */
    fun isUsablePair(apiId: Int, apiHash: String): Boolean =
        apiId > 0 && normalizeApiHash(apiHash) != null

    private fun userCredentials(apiId: Int?, apiHash: String?): TelegramCredentials? {
        if (apiId == null || apiHash.isNullOrBlank()) return null
        if (!isUsablePair(apiId, apiHash)) return null
        return TelegramCredentials(apiId, normalizeApiHash(apiHash)!!, CredentialSource.USER_PROVIDED)
    }

    private fun buildCredentials(apiId: Int, apiHash: String): TelegramCredentials? {
        if (!isUsablePair(apiId, apiHash)) return null
        return TelegramCredentials(apiId, normalizeApiHash(apiHash)!!, CredentialSource.BUILD_CONFIG)
    }
}
