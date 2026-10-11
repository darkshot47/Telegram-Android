package com.telefarm.ui.lock

import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.data.PasscodeRepository
import com.telefarm.data.UnlockResult
import com.telefarm.databinding.ActivityLockBinding
import kotlinx.coroutines.launch

/**
 * The screen in front of the chat list.
 *
 * It is the whole of the app while the passcode has not been entered: no chat, no list, no
 * notification content behind it, and no way back. The window is kept out of screenshots and out
 * of the task switcher, so the passcode is not captured on the way in.
 */
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding

    private val passcodes: PasscodeRepository get() = appGraph.passcodes

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        // Back must not walk out of the lock screen into the app behind it.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        binding.unlockButton.setOnClickListener { submit() }
        binding.passcodeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }

        // Opening the lock screen when nothing is locked would be a dead end.
        if (!passcodes.needsPasscodeNow()) {
            finish()
        }
    }

    private fun submit() {
        val attempt = binding.passcodeInput.text?.toString().orEmpty()
        binding.unlockButton.isEnabled = false
        lifecycleScope.launch {
            when (val result = passcodes.unlock(attempt)) {
                is UnlockResult.Opened -> finish()
                is UnlockResult.Wrong -> {
                    binding.passcodeInput.text = null
                    showError(
                        if (result.attemptsLeft > 0) {
                            getString(R.string.lock_error_wrong)
                        } else {
                            getString(R.string.lock_error_last_attempt)
                        }
                    )
                    binding.unlockButton.isEnabled = true
                }
                is UnlockResult.Waiting -> {
                    binding.passcodeInput.text = null
                    showError(getString(R.string.lock_error_waiting, describeWait(result.remainingMs)))
                    binding.unlockButton.isEnabled = true
                }
            }
        }
    }

    private fun showError(message: String) {
        binding.lockError.text = message
        binding.lockError.isVisible = true
    }

    /** A wait as a person reads it: seconds under a minute, minutes above it. */
    private fun describeWait(remainingMs: Long): String {
        val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(1)
        return if (seconds < SECONDS_PER_MINUTE) {
            getString(R.string.lock_wait_seconds, seconds)
        } else {
            val minutes = (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
            getString(R.string.lock_wait_minutes, minutes)
        }
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60
    }
}
