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

/** Credentials of this Telegram application; never persisted. */
data class TelegramCredentials(val apiId: Int, val apiHash: String) {
    val isConfigured: Boolean get() = apiId != 0 && apiHash.isNotBlank()
}
