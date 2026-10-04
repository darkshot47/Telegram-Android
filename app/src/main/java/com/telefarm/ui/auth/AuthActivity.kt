package com.telefarm.ui.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.databinding.ActivityAuthBinding
import com.telefarm.ui.common.hideKeyboard
import com.telefarm.ui.common.resolve
import com.telefarm.ui.common.setVisible
import com.telefarm.ui.main.MainActivity
import kotlinx.coroutines.launch

/**
 * Sign-in flow: phone number, verification code, optional two-step password, email based
 * logins and account registration.
 *
 * Every step renders its own loading and error state; nothing sensitive is ever logged or
 * shown outside the input fields the user is typing into.
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding

    private val viewModel: AuthViewModel by viewModels {
        viewModelFactory { initializer { AuthViewModel(appGraph) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSteps()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun setupSteps() {
        binding.authPhone.phoneInput.doAfterTextChanged { editable ->
            val formatted = PhoneNumberFormatter.asYouType(editable?.toString().orEmpty())
            if (formatted != editable?.toString()) {
                binding.authPhone.phoneInput.setText(formatted)
                binding.authPhone.phoneInput.setSelection(formatted.length)
            }
        }
        binding.authPhone.phoneButton.setOnClickListener {
            binding.authPhone.phoneInput.hideKeyboard()
            viewModel.submitPhoneNumber(binding.authPhone.phoneInput.text?.toString().orEmpty())
        }

        binding.authCode.codeButton.setOnClickListener {
            binding.authCode.codeInput.hideKeyboard()
            viewModel.submitCode(binding.authCode.codeInput.text?.toString().orEmpty())
        }
        binding.authCode.resendButton.setOnClickListener { viewModel.resendCode() }

        binding.authPassword.passwordButton.setOnClickListener {
            binding.authPassword.passwordInput.hideKeyboard()
            viewModel.submitPassword(binding.authPassword.passwordInput.text?.toString().orEmpty())
        }

        binding.authEmail.emailButton.setOnClickListener {
            binding.authEmail.emailInput.hideKeyboard()
            val codeStep = viewModel.state.value.step == AuthStep.EMAIL_CODE
            if (codeStep) {
                viewModel.submitEmailCode(binding.authEmail.emailCodeInput.text?.toString().orEmpty())
            } else {
                viewModel.submitEmailAddress(binding.authEmail.emailInput.text?.toString().orEmpty())
            }
        }

        binding.authOtherDevice.confirmationLink.setOnClickListener {
            val link = viewModel.state.value.confirmationLink ?: return@setOnClickListener
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
        }

        binding.authRegister.registerButton.setOnClickListener {
            viewModel.register(
                binding.authRegister.firstNameInput.text?.toString().orEmpty(),
                binding.authRegister.lastNameInput.text?.toString().orEmpty()
            )
        }
    }

    private fun render(state: AuthFormState) {
        binding.authProgress.setVisible(
            state.step == AuthStep.CHECKING || (state.isBusy && state.step != AuthStep.READY)
        )
        binding.authError.setVisible(state.error != null)
        binding.authError.text = state.error?.resolve(this)

        binding.authPhone.root.setVisible(state.step == AuthStep.PHONE_NUMBER)
        binding.authCode.root.setVisible(state.step == AuthStep.CODE)
        binding.authPassword.root.setVisible(state.step == AuthStep.PASSWORD)
        binding.authEmail.root.setVisible(state.step == AuthStep.EMAIL_ADDRESS || state.step == AuthStep.EMAIL_CODE)
        binding.authOtherDevice.root.setVisible(state.step == AuthStep.OTHER_DEVICE)
        binding.authRegister.root.setVisible(state.step == AuthStep.REGISTRATION)

        when (state.step) {
            AuthStep.CODE -> renderCodeStep(state)
            AuthStep.PASSWORD -> renderPasswordStep(state)
            AuthStep.EMAIL_ADDRESS, AuthStep.EMAIL_CODE -> renderEmailStep(state)
            AuthStep.OTHER_DEVICE -> binding.authOtherDevice.confirmationLink.text = state.confirmationLink.orEmpty()
            AuthStep.REGISTRATION -> renderRegistrationStep(state)
            AuthStep.READY -> openMainScreen()
            else -> Unit
        }
    }

    private fun renderCodeStep(state: AuthFormState) {
        binding.authCode.codeSubtitle.text = getString(R.string.auth_code_subtitle, state.phoneNumber)
        binding.authCode.codeHint.setVisible(state.codeHint != null)
        binding.authCode.codeHint.text = state.codeHint?.resolve(this)
        state.codeLength?.let { length ->
            binding.authCode.codeInput.filters = arrayOf(InputFilter.LengthFilter(length.coerceAtLeast(1)))
        }
        binding.authCode.resendButton.isEnabled = state.canResendCode
        binding.authCode.resendButton.text = if (state.resendSecondsRemaining > 0) {
            getString(R.string.auth_resend_in, state.resendSecondsRemaining)
        } else {
            getString(R.string.auth_resend_code)
        }
    }

    private fun renderPasswordStep(state: AuthFormState) {
        binding.authPassword.passwordHint.setVisible(state.passwordHint != null)
        binding.authPassword.passwordHint.text = state.passwordHint?.let {
            getString(R.string.auth_password_hint_label, it)
        }
        binding.authPassword.passwordRecovery.setVisible(state.hasRecoveryEmail)
    }

    private fun renderEmailStep(state: AuthFormState) {
        val isCodeStep = state.step == AuthStep.EMAIL_CODE
        binding.authEmail.emailLabel.setVisible(!isCodeStep)
        binding.authEmail.emailInput.setVisible(!isCodeStep)
        binding.authEmail.emailCodeInput.setVisible(isCodeStep)
        state.codeLength?.let { length ->
            binding.authEmail.emailCodeInput.filters = arrayOf(InputFilter.LengthFilter(length.coerceAtLeast(1)))
        }
        binding.authEmail.emailButton.setText(if (isCodeStep) R.string.auth_verify else R.string.auth_continue)
    }

    private fun renderRegistrationStep(state: AuthFormState) {
        val terms = state.termsOfService
        binding.authRegister.termsText.setVisible(!terms.isNullOrBlank())
        binding.authRegister.termsText.text = terms.orEmpty()
    }

    private fun openMainScreen() {
        // The session is authorized: hand over to the chat list and leave the sign-in flow.
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }
}
