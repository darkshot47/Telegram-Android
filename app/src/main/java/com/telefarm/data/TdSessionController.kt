package com.telefarm.data

import com.telefarm.R
import com.telefarm.core.mtproto.MtProtoClient
import com.telefarm.core.mtproto.MtProtoClientInfo
import com.telefarm.core.mtproto.MtProtoResponse
import com.telefarm.core.security.SecureStore
import com.telefarm.core.session.ImportedSession
import com.telefarm.core.session.LoginToken
import com.telefarm.core.session.SessionLoginException
import com.telefarm.core.td.TdLibClient
import com.telefarm.core.td.TelefarmLog
import com.telefarm.data.map.TdMappers
import com.telefarm.data.model.AuthState
import com.telefarm.data.model.ConnectionStatus
import com.telefarm.data.model.TelegramCredentials
import com.telefarm.data.model.UiMessage
import com.telefarm.data.model.UserUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the TDLib session lifecycle: parameters, authorization state machine, encryption key
 * and logout.
 *
 * The session lives in the private application directory, encrypted with a key that is kept in
 * the Android keystore. On start TDLib reuses that directory, so an authorized user goes
 * straight to the chat list without signing in again.
 */
class TdSessionController(
    private val client: TdLibClient,
    private val secureStore: SecureStore,
    private val credentials: TelegramCredentials,
    private val workspaceDirectory: File,
    private val applicationVersion: String,
    private val scope: CoroutineScope
) {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initializing)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTING)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _selfUserId = MutableStateFlow(0L)
    val selfUserId: StateFlow<Long> = _selfUserId.asStateFlow()

    private val _profile = MutableStateFlow<UserUi?>(null)

    /** Profile of the signed in account, refreshed after every relevant update. */
    val profile: StateFlow<UserUi?> = _profile.asStateFlow()

    private val parametersSent = AtomicBoolean(false)

    /** True when the api id and hash were provided at build time. */
    val credentialsConfigured: Boolean get() = credentials.isConfigured

    fun start() {
        scope.launch {
            client.updates.collect(::handleUpdate)
        }
        scope.launch {
            // The client answers with its current state, which also covers a restored session.
            runCatching { client.send(TdApi.GetAuthorizationState()) }
        }
    }

    // region Sign in

    /** Sends the phone number to Telegram and requests a login code. */
    suspend fun submitPhoneNumber(phoneNumber: String): Result<Unit> = request {
        client.send(
            TdApi.SetAuthenticationPhoneNumber().apply {
                this.phoneNumber = phoneNumber
                settings = TdApi.PhoneNumberAuthenticationSettings().apply {
                    allowFlashCall = false
                    allowMissedCall = true
                    isCurrentPhoneNumber = false
                    hasUnknownPhoneNumber = true
                    allowSmsRetrieverApi = false
                    firebaseAuthenticationSettings = null
                    authenticationTokens = emptyArray()
                }
            }
        )
    }

    /** Verifies the login code sent by Telegram. */
    suspend fun submitCode(code: String): Result<Unit> = request {
        client.send(TdApi.CheckAuthenticationCode().apply { this.code = code.trim() })
    }

    /** Asks Telegram for a new login code. */
    suspend fun resendCode(): Result<Unit> = request {
        client.send(TdApi.ResendAuthenticationCode().apply { reason = TdApi.ResendCodeReasonUserRequest() })
    }

    /** Verifies the two step verification password. */
    suspend fun submitPassword(password: String): Result<Unit> = request {
        client.send(TdApi.CheckAuthenticationPassword().apply { this.password = password })
    }

    /** Tells Telegram the email address a login code should be sent to. */
    suspend fun submitEmailAddress(emailAddress: String): Result<Unit> = request {
        client.send(TdApi.SetAuthenticationEmailAddress().apply { this.emailAddress = emailAddress.trim() })
    }

    /** Verifies the code sent by email. */
    suspend fun submitEmailCode(code: String): Result<Unit> = request {
        client.send(
            TdApi.CheckAuthenticationEmailCode().apply {
                this.code = TdApi.EmailAddressAuthenticationCode().apply { this.code = code.trim() }
            }
        )
    }

    /** Creates the account for a phone number that is not registered yet. */
    suspend fun register(firstName: String, lastName: String): Result<Unit> = request {
        client.send(
            TdApi.RegisterUser().apply {
                this.firstName = firstName.trim()
                this.lastName = lastName.trim()
                disableNotification = true
            }
        )
    }

    /**
     * Asks Telegram for a login token through TDLib.
     *
     * TDLib answers with `authorizationStateWaitOtherDeviceConfirmation`, and the link of that
     * state carries the token. Works while the sign in screen waits for a phone number.
     */
    suspend fun requestSessionLogin(): Result<Unit> = request {
        client.send(TdApi.RequestQrCodeAuthentication().apply { otherUserIds = longArrayOf() })
    }

    /**
     * Approves the login token of [link] with the authorization key of [session].
     *
     * TDLib cannot import an authorization key, so the pasted session is used the way an already
     * signed in client approves a login: it signs the token on the MTProto connection of its own
     * data center. TDLib then finishes the login on its own and reports the ready state.
     */
    suspend fun acceptLoginToken(session: ImportedSession, link: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            request {
                val token = LoginToken.fromLink(link)
                val error = (mtProtoClient().acceptLoginToken(session, token) as? MtProtoResponse.Error)
                if (error != null) {
                    throw SessionLoginException(error.code, error.message)
                }
            }
        }

    /** The client that signs the login token; it carries no secret of its own. */
    private fun mtProtoClient(): MtProtoClient = MtProtoClient(
        MtProtoClientInfo(
            apiId = credentials.apiId,
            deviceModel = android.os.Build.MODEL.ifBlank { DEVICE_MODEL_FALLBACK },
            systemVersion = android.os.Build.VERSION.RELEASE.ifBlank { SYSTEM_VERSION_FALLBACK },
            appVersion = applicationVersion,
            systemLanguageCode = "en",
            languageCode = java.util.Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en"
        )
    )

    /** Signs out and removes every local trace of the session. */
    suspend fun logOut() {
        runCatching { client.send(TdApi.LogOut()) }
        _selfUserId.value = 0L
        _profile.value = null
        scope.launch {
            if (!waitForClosed(attempts = LOGOUT_CLOSE_ATTEMPTS)) {
                // A logout needs the network. If it did not finish, the instance is closed instead:
                // closing always flushes the database and ends in the state the flow waits for.
                withTimeoutOrNull(CLOSE_SEND_TIMEOUT_MS) { client.send(TdApi.Close()) }
                waitForClosed(attempts = LOGOUT_CLOSE_ATTEMPTS)
            }
            // The database is closed now, so the files and the key that decrypts them can go.
            secureStore.remove(KEY_DATABASE)
            deleteSessionDirectory()
            // Logging out closes the TDLib instance: it is destroyed once it reaches
            // authorizationStateClosed and cannot answer any request after that. Without a new
            // instance the sign in screen would show its steps while every request disappears,
            // which is exactly what "the Continue button does nothing" looked like. The parameters
            // have to be sent again for the new instance.
            parametersSent.set(false)
            client.restart()
            _authState.value = AuthState.Initializing
            runCatching { client.send(TdApi.GetAuthorizationState()) }
        }
    }

    /** Reports a failure that happened before TDLib could be used at all. */
    fun reportNativeFailure(error: Throwable) {
        TelefarmLog.e(TAG, "TDLib is not usable", error)
        _authState.value = AuthState.Failed(UiMessage.Res(R.string.error_telegram))
    }

    // endregion

    // region State

    private suspend fun handleUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuthorizationState(update.authorizationState)
            is TdApi.UpdateConnectionState -> _connectionStatus.value = when (update.state) {
                is TdApi.ConnectionStateReady -> ConnectionStatus.READY
                is TdApi.ConnectionStateUpdating -> ConnectionStatus.UPDATING
                is TdApi.ConnectionStateConnecting, is TdApi.ConnectionStateConnectingToProxy ->
                    ConnectionStatus.CONNECTING
                is TdApi.ConnectionStateWaitingForNetwork -> ConnectionStatus.WAITING_FOR_NETWORK
                else -> ConnectionStatus.CONNECTING
            }

            is TdApi.UpdateUser -> if (update.user.id == _selfUserId.value) {
                _profile.value = TdMappers.user(update.user)
            }

            else -> Unit
        }
    }

    private suspend fun onAuthorizationState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendParameters()

            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                _authState.value = AuthState.WaitPhoneNumber
                _selfUserId.value = 0L
            }

            is TdApi.AuthorizationStateWaitCode -> {
                val info = state.codeInfo
                _authState.value = AuthState.WaitCode(
                    codeLength = AuthenticationCodeLength.of(info?.type),
                    codeType = codeTypeOf(info?.type),
                    resendInSeconds = info?.timeout?.coerceAtLeast(0) ?: 0
                )
            }

            is TdApi.AuthorizationStateWaitPassword -> _authState.value = AuthState.WaitPassword(
                passwordHint = state.passwordHint?.takeIf { it.isNotBlank() },
                hasRecoveryEmail = state.hasRecoveryEmailAddress
            )

            is TdApi.AuthorizationStateWaitEmailAddress -> _authState.value = AuthState.WaitEmailAddress(
                allowAppleId = state.allowAppleId,
                allowGoogleId = state.allowGoogleId
            )

            is TdApi.AuthorizationStateWaitEmailCode -> _authState.value = AuthState.WaitEmailCode(
                codeLength = state.codeInfo?.length?.takeIf { it > 0 },
                emailPattern = state.codeInfo?.emailAddressPattern?.takeIf { it.isNotBlank() }
            )

            is TdApi.AuthorizationStateWaitRegistration -> _authState.value = AuthState.WaitRegistration(
                termsOfService = state.termsOfService?.text?.text
            )

            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> _authState.value =
                AuthState.WaitOtherDeviceConfirmation(state.link.orEmpty())

            is TdApi.AuthorizationStateReady -> {
                _authState.value = AuthState.Ready
                refreshSelf()
            }

            is TdApi.AuthorizationStateLoggingOut -> _authState.value = AuthState.LoggingOut

            is TdApi.AuthorizationStateClosing -> _authState.value = AuthState.LoggingOut

            is TdApi.AuthorizationStateClosed -> {
                _authState.value = AuthState.Closed
                deleteSessionDirectory()
            }

            else -> Unit
        }
    }

    private suspend fun sendParameters() {
        if (!parametersSent.compareAndSet(false, true)) return
        if (!credentials.isConfigured) {
            // Without credentials nothing can be authorized; the sign in screen explains it.
            _authState.value = AuthState.Failed(UiMessage.Res(R.string.error_credentials))
            return
        }
        val databaseDirectory = File(workspaceDirectory, DIRECTORY_DATABASE)
        val filesDirectory = File(workspaceDirectory, DIRECTORY_FILES)
        val key = databaseKey()
        try {
            client.send(
                TdApi.SetTdlibParameters().apply {
                    useTestDc = false
                    this.databaseDirectory = databaseDirectory.absolutePath
                    this.filesDirectory = filesDirectory.absolutePath
                    databaseEncryptionKey = key
                    useFileDatabase = true
                    useChatInfoDatabase = true
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = credentials.apiId
                    apiHash = credentials.apiHash
                    systemLanguageCode = "en"
                    deviceModel = android.os.Build.MODEL.ifBlank { DEVICE_MODEL_FALLBACK }
                    systemVersion = android.os.Build.VERSION.RELEASE.ifBlank { SYSTEM_VERSION_FALLBACK }
                    applicationVersion = applicationVersion
                }
            )
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            parametersSent.set(false)
            _authState.value = AuthState.Failed(UiMessage.Res(R.string.error_network))
        }
    }

    /**
     * Key used to encrypt the local TDLib database.
     *
     * The key is random per installation and stored in the keystore backed secure store; it is
     * never derived from the phone number or any other user data.
     */
    private fun databaseKey(): ByteArray {
        val stored = secureStore.get(KEY_DATABASE)
        if (stored != null && stored.length >= MIN_KEY_LENGTH) {
            return stored.toByteArray(Charsets.ISO_8859_1)
        }
        val generated = ByteArray(KEY_LENGTH_BYTES)
        java.security.SecureRandom().nextBytes(generated)
        val encoded = String(generated, Charsets.ISO_8859_1)
        secureStore.put(KEY_DATABASE, encoded)
        return generated
    }

    private suspend fun refreshSelf() {
        val me = runCatching { client.send(TdApi.GetMe()) }.getOrNull() ?: return
        _selfUserId.value = me.id
        _profile.value = TdMappers.user(me)
    }

    /** Waits until TDLib reports the closed state; false when it does not arrive in time. */
    private suspend fun waitForClosed(attempts: Int): Boolean {
        repeat(attempts) {
            if (_authState.value is AuthState.Closed) return true
            delay(CLOSE_POLL_INTERVAL_MS)
        }
        return false
    }

    private fun deleteSessionDirectory() {
        runCatching {
            File(workspaceDirectory, DIRECTORY_DATABASE).deleteRecursively()
            File(workspaceDirectory, DIRECTORY_FILES).deleteRecursively()
        }
    }

    private suspend fun request(block: suspend () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        Result.failure(error)
    }

    private fun codeTypeOf(type: TdApi.AuthenticationCodeType?): AuthState.CodeType = when (type) {
        is TdApi.AuthenticationCodeTypeSms -> AuthState.CodeType.SMS
        is TdApi.AuthenticationCodeTypeCall -> AuthState.CodeType.CALL
        is TdApi.AuthenticationCodeTypeFlashCall -> AuthState.CodeType.FLASH_CALL
        is TdApi.AuthenticationCodeTypeMissedCall -> AuthState.CodeType.CALL
        is TdApi.AuthenticationCodeTypeTelegramMessage -> AuthState.CodeType.OTHER
        null -> AuthState.CodeType.UNKNOWN
        else -> AuthState.CodeType.OTHER
    }

    private companion object {
        const val TAG = "TdSession"
        const val KEY_DATABASE = "tdlib.database.key"
        const val DIRECTORY_DATABASE = "db"
        const val DIRECTORY_FILES = "files"
        const val KEY_LENGTH_BYTES = 32
        const val MIN_KEY_LENGTH = 16
        const val DEVICE_MODEL_FALLBACK = "Android"
        const val SYSTEM_VERSION_FALLBACK = "Android"
        const val LOGOUT_CLOSE_ATTEMPTS = 40
        const val CLOSE_SEND_TIMEOUT_MS = 3_000L
        const val CLOSE_POLL_INTERVAL_MS = 100L
    }
}

/** Login code length reported by Telegram, when the type carries one. */
private object AuthenticationCodeLength {
    fun of(type: TdApi.AuthenticationCodeType?): Int? = when (type) {
        is TdApi.AuthenticationCodeTypeSms -> type.length
        is TdApi.AuthenticationCodeTypeCall -> type.length
        is TdApi.AuthenticationCodeTypeFlashCall -> null
        is TdApi.AuthenticationCodeTypeMissedCall -> type.length
        is TdApi.AuthenticationCodeTypeTelegramMessage -> type.length
        is TdApi.AuthenticationCodeTypeSmsWord -> null
        is TdApi.AuthenticationCodeTypeSmsPhrase -> null
        else -> null
    }?.takeIf { it > 0 }
}
