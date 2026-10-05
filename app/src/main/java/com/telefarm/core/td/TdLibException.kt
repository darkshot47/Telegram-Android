package com.telefarm.core.td

import org.drinkless.tdlib.TdApi

/**
 * Errors raised while talking to TDLib.
 *
 * The messages of these exceptions are for developers only: the UI maps them to localized
 * strings and never prints raw TDLib output, which can contain request parameters.
 */
sealed class TdLibException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** TDLib was never started. */
    class ClientNotStarted : TdLibException("TDLib client is not running")

    /** The native library is missing, usually because of an unsupported ABI. */
    class NativeLibraryUnavailable(cause: Throwable) :
        TdLibException("TDLib native library is unavailable", cause)

    /** TDLib answered with an error; [error] carries the code and message. */
    class RequestFailed(
        val function: TdApi.Function<*>,
        val error: Throwable
    ) : TdLibException("TDLib request failed", error)

    /** Code and description reported by TDLib, when the error came from an API response. */
    val apiError: TdApi.Error?
        get() = (this as? RequestFailed)?.error as? TdApi.Error
}

/** Human readable code of a TDLib error, for example `400`. */
val TdApi.Error.codeLabel: String get() = code.toString()

/** True when Telegram asks the client to slow down before retrying. */
val TdApi.Error.isFloodWait: Boolean get() = code == FLOOD_WAIT_CODE

const val FLOOD_WAIT_CODE = 429
