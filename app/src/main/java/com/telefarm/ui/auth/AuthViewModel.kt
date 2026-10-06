package com.telefarm.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telefarm.R
import com.telefarm.core.AppGraph
import com.telefarm.core.td.TdLibException
import com.telefarm.data.map.toAuthMessage
import com.telefarm.data.model.AuthState
import com.telefarm.data.model.UiMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Screen the sign in flow currently shows. */
enum class AuthStep {
    CHECKING,
    PHONE_NUMBER,
    CODE,
    PASSWORD,
    EMAIL_ADDRESS,
    EMAIL_CODE,
    OTHER_DEVICE,
    REGISTRATION,
    READY,
    FAILED
}

/** Everything the sign in screens render. */
data class AuthFormState(
    val step: AuthStep = AuthStep.CHECKING,
    val isBusy: Boolean = false,
    val error: UiMessage? = null,
    val phoneNumber: String = "",
    val codeLength: Int? = null,
    val codeType: AuthState.CodeType? = null,
    val resendInSeconds: Int = 0,
    val passwordHint: String? = null,
    val hasRecoveryEmail: Boolean = false,
    val emailPattern: String? = null,
    val confirmationLink: String? = null,
    val termsOfService: String? = null,
    val credentialsConfigured: Boolean = true
) {
    val canSubmit: Boolean get() = !isBusy
}

/**
 * Sign in logic.
 *
 * The view model only mirrors [TdSessionController]; every state change comes from TDLib, so
 * the screen cannot show a step Telegram has not actually reached.
 */
class AuthViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(
        AuthFormState(
            step = AuthStep.CHECKING,
            credentialsConfigured = graph.session.credentialsConfigured
        )
    )
    val state: StateFlow<AuthFormState> = _state.asStateFlow()

    private var resendJob: Job? = null
    private var submittedPhone: String = ""

    init {
        viewModelScope.launch {
            graph.session.authState.collect { authState -> render(authState) }
        }
    }

    /** Sends the phone number; the number is kept only to label the next screen. */
    fun submitPhoneNumber(rawNumber: String) {
        val normalized = PhoneNumberFormatter.normalize(rawNumber)
        if (normalized == null || !PhoneNumberFormatter.isValid(normalized)) {
            _state.value = _state.value.copy(error = UiMessage.Res(R.string.auth_error_phone_invalid))
            return
        }
        submittedPhone = normalized
        perform {
            graph.session.submitPhoneNumber(normalized)
        }
    }

    fun submitCode(code: String) {
        if (code.length < MIN_CODE_LENGTH) {
            _state.value = _state.value.copy(error = UiMessage.Res(R.string.auth_error_code_invalid))
            return
        }
        perform { graph.session.submitCode(code) }
    }

    fun resendCode() {
        if (!_state.value.canSubmit || _state.value.resendInSeconds > 0) return
        perform { graph.session.resendCode() }
    }

    fun submitPassword(password: String) {
        if (password.isBlank()) {
            _state.value = _state.value.copy(error = UiMessage.Res(R.string.auth_error_password_invalid))
            return
        }
        perform { graph.session.submitPassword(password) }
    }

    fun submitEmailAddress(email: String) {
        if (!email.contains('@')) {
            _state.value = _state.value.copy(error = UiMessage.Res(R.string.auth_error_generic))
            return
        }
        perform { graph.session.submitEmailAddress(email) }
    }

    fun submitEmailCode(code: String) {
        if (code.isBlank()) {
            _state.value = _state.value.copy(error = UiMessage.Res(R.string.auth_error_code_invalid))
            return
        }
        perform { graph.session.submitEmailCode(code) }
    }

    fun register(firstName: String, lastName: String) {
        if (firstName.isBlank()) {
            _state.value = _state.value.copy(error = UiMessage.Res(R.string.auth_profile_name_required))
            return
        }
        perform { graph.session.register(firstName, lastName) }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun perform(block: suspend () -> Result<Unit>) {
        if (_state.value.isBusy) return
        _state.value = _state.value.copy(isBusy = true, error = null)
        viewModelScope.launch {
            val result = block()
            result.exceptionOrNull()?.let { error ->
                _state.value = _state.value.copy(error = authMessage(error))
            }
            _state.value = _state.value.copy(isBusy = false)
        }
    }

    /** Maps a failure reported by the session to the text the sign in screen shows. */
    private fun authMessage(error: Throwable): UiMessage =
        if (error is TdLibException) error.toAuthMessage()
        else UiMessage.Res(R.string.auth_error_network)

    private fun render(authState: AuthState) {
        _state.value = when (authState) {
            AuthState.Initializing -> _state.value.copy(step = AuthStep.CHECKING, isBusy = true)

            AuthState.WaitPhoneNumber -> _state.value.copy(
                step = AuthStep.PHONE_NUMBER,
                isBusy = false,
                codeLength = null,
                codeType = null,
                resendInSeconds = 0
            )

            is AuthState.WaitCode -> {
                startResendCountdown(authState.resendInSeconds)
                _state.value.copy(
                    step = AuthStep.CODE,
                    isBusy = false,
                    phoneNumber = submittedPhone,
                    codeLength = authState.codeLength,
                    codeType = authState.codeType,
                    resendInSeconds = authState.resendInSeconds
                )
            }

            is AuthState.WaitPassword -> _state.value.copy(
                step = AuthStep.PASSWORD,
                isBusy = false,
                passwordHint = authState.passwordHint,
                hasRecoveryEmail = authState.hasRecoveryEmail
            )

            is AuthState.WaitEmailAddress -> _state.value.copy(
                step = AuthStep.EMAIL_ADDRESS,
                isBusy = false
            )

            is AuthState.WaitEmailCode -> _state.value.copy(
                step = AuthStep.EMAIL_CODE,
                isBusy = false,
                codeLength = authState.codeLength,
                emailPattern = authState.emailPattern
            )

            is AuthState.WaitRegistration -> _state.value.copy(
                step = AuthStep.REGISTRATION,
                isBusy = false,
                termsOfService = authState.termsOfService
            )

            is AuthState.WaitOtherDeviceConfirmation -> _state.value.copy(
                step = AuthStep.OTHER_DEVICE,
                isBusy = false,
                confirmationLink = authState.link
            )

            AuthState.Ready -> _state.value.copy(step = AuthStep.READY, isBusy = false, error = null)

            AuthState.LoggingOut -> _state.value.copy(step = AuthStep.CHECKING, isBusy = true, error = null)

            AuthState.Closed -> _state.value.copy(
                step = AuthStep.PHONE_NUMBER,
                isBusy = false,
                error = UiMessage.Res(R.string.auth_error_restart)
            )

            is AuthState.Failed -> _state.value.copy(
                step = AuthStep.FAILED,
                isBusy = false,
                error = authState.message
            )
        }
    }

    /** Counts the seconds Telegram asks the client to wait before a new code can be sent. */
    private fun startResendCountdown(seconds: Int) {
        resendJob?.cancel()
        if (seconds <= 0) {
            _state.value = _state.value.copy(resendInSeconds = 0)
            return
        }
        resendJob = viewModelScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                _state.value = _state.value.copy(resendInSeconds = remaining)
                delay(1_000L)
                remaining--
            }
            _state.value = _state.value.copy(resendInSeconds = 0)
        }
    }

    /** Shows a failure reported by the network layer. */
    fun reportFailure(error: Throwable) {
        _state.value = _state.value.copy(isBusy = false, error = authMessage(error))
    }

    private companion object {
        const val MIN_CODE_LENGTH = 3
    }
}
