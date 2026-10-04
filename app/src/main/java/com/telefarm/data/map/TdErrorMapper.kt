package com.telefarm.data.map

import com.telefarm.R
import com.telefarm.core.td.FLOOD_WAIT_CODE
import com.telefarm.core.td.TdLibException
import com.telefarm.data.model.UiMessage

/** Longest message Telegram accepts for a single text message. */
const val MAX_MESSAGE_LENGTH = 4096

/**
 * Turns TDLib and Telegram errors into messages a user can understand.
 *
 * The original error text is never shown: it contains API specific codes and, for some
 * requests, echoes of the request parameters.
 */
fun TdLibException.toUiMessage(): UiMessage {
    val apiError = apiError
    return when {
        this is TdLibException.NativeLibraryUnavailable ->
            UiMessage.Res(R.string.error_telegram)

        apiError == null -> UiMessage.Res(R.string.error_network)

        apiError.code == FLOOD_WAIT_CODE -> {
            val seconds = parseFloodWait(apiError.message)
            UiMessage.Res(R.string.error_flood_wait, listOf(seconds))
        }

        apiError.message?.contains("FORBIDDEN", ignoreCase = true) == true ->
            UiMessage.Res(R.string.error_chat_forbidden)

        apiError.message?.contains("UNAUTHORIZED", ignoreCase = true) == true ->
            UiMessage.Res(R.string.auth_error_restart)

        else -> UiMessage.Res(R.string.error_telegram)
    }
}

/** Number of seconds Telegram asks the client to wait. */
private fun parseFloodWait(message: String?): Int {
    val digits = message?.filter { it.isDigit() } ?: return DEFAULT_FLOOD_WAIT_SECONDS
    return digits.toIntOrNull()?.takeIf { it in 1..MAX_WAIT_SECONDS } ?: DEFAULT_FLOOD_WAIT_SECONDS
}

private const val DEFAULT_FLOOD_WAIT_SECONDS = 30
private const val MAX_WAIT_SECONDS = 3600

/** Maps an authorization error to the message shown by the sign in screen. */
fun TdLibException.toAuthMessage(): UiMessage {
    val apiError = apiError ?: return UiMessage.Res(R.string.auth_error_network)
    val message = apiError.message.orEmpty().uppercase()
    return when {
        "PHONE_CODE_INVALID" in message -> UiMessage.Res(R.string.auth_error_code_invalid)
        "PHONE_CODE_EXPIRED" in message -> UiMessage.Res(R.string.auth_error_code_expired)
        "PHONE_NUMBER_INVALID" in message -> UiMessage.Res(R.string.auth_error_phone_invalid)
        "PHONE_NUMBER_BANNED" in message -> UiMessage.Res(R.string.auth_error_phone_banned)
        "PASSWORD_HASH_INVALID" in message || "INVALID_PASSWORD" in message ->
            UiMessage.Res(R.string.auth_error_password_invalid)
        apiError.code == FLOOD_WAIT_CODE ->
            UiMessage.Res(R.string.auth_error_flood, listOf(parseFloodWait(apiError.message)))
        "NETWORK" in message -> UiMessage.Res(R.string.auth_error_network)
        else -> UiMessage.Res(R.string.auth_error_generic)
    }
}

/** True when the error means the current session is no longer valid. */
fun Throwable.isUnauthorized(): Boolean {
    val apiError = (this as? TdLibException)?.apiError ?: return false
    return apiError.code == UNAUTHORIZED_CODE || apiError.message?.contains("UNAUTHORIZED", true) == true
}

/** Code Telegram uses for an invalid or expired session. */
const val UNAUTHORIZED_CODE = 401
