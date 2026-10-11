package com.telefarm.data.model

/**
 * Authentication state of the TDLib client.
 *
 * The states mirror the TDLib authorization states that need their own screen; the remaining
 * intermediate states are represented by [WaitOtherDeviceConfirmation] and [Closed].
 */
sealed interface AuthState {
    data object Initializing : AuthState
    data object WaitPhoneNumber : AuthState
    data class WaitCode(
        val codeLength: Int?,
        val codeType: CodeType,
        val resendInSeconds: Int = 0
    ) : AuthState
    data class WaitPassword(val passwordHint: String?, val hasRecoveryEmail: Boolean) : AuthState
    data class WaitEmailAddress(val allowAppleId: Boolean, val allowGoogleId: Boolean) : AuthState
    data class WaitEmailCode(val codeLength: Int?, val emailPattern: String?) : AuthState
    data class WaitRegistration(val termsOfService: String?) : AuthState
    data class WaitOtherDeviceConfirmation(val link: String) : AuthState
    data object Ready : AuthState
    data object LoggingOut : AuthState
    data object Closed : AuthState
    data class Failed(val message: UiMessage) : AuthState

    /** How Telegram delivered the login code. */
    enum class CodeType { SMS, CALL, FLASH_CALL, OTHER, UNKNOWN }
}

/** Where the credentials the client signs in with came from. */
enum class CredentialSource {

    /** Entered on the sign in screen of this device and kept in the secure store. */
    USER_PROVIDED,

    /** Compiled into this build through `TELEGRAM_API_ID` / `TELEGRAM_API_HASH`. */
    BUILD_CONFIG,

    /** The shared identity the application ships with, so it works right after installing. */
    BUILT_IN
}

/**
 * Credentials of this Telegram application; never written to a log and never shown in full.
 *
 * [source] only says where they came from, which is what the sign in screen displays.
 */
data class TelegramCredentials(
    val apiId: Int,
    val apiHash: String,
    val source: CredentialSource = CredentialSource.BUILD_CONFIG
) {
    val isConfigured: Boolean get() = apiId != 0 && apiHash.isNotBlank()

    /** Short label for the interface; the hash itself is never displayed. */
    fun describe(): String = if (isConfigured) "api id $apiId" else "not configured"
}
