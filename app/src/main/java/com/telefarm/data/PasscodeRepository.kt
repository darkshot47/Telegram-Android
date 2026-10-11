package com.telefarm.data

import com.telefarm.core.prefs.PasscodeStore
import com.telefarm.core.prefs.StoredPasscode
import com.telefarm.core.security.AutoLockDelay
import com.telefarm.core.security.LockGuard
import com.telefarm.core.security.LockState
import com.telefarm.core.security.PasscodeCheck
import com.telefarm.core.security.PasscodeHash
import com.telefarm.core.security.PasscodePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** What setting a passcode produced. */
sealed interface PasscodeSetResult {

    /** The passcode is in use now. */
    data object Set : PasscodeSetResult

    /** The passcode did not pass [PasscodePolicy]. */
    data class Refused(val check: PasscodeCheck) : PasscodeSetResult

    /** The confirmation field did not match. */
    data object ConfirmationMismatch : PasscodeSetResult

    /** The passcode already in use was not the one typed. */
    data object CurrentPasscodeWrong : PasscodeSetResult
}

/** What an attempt at the lock screen produced. */
sealed interface UnlockResult {

    /** The passcode was right and the app is open. */
    data object Opened : UnlockResult

    /** The passcode was wrong. [attemptsLeft] counts down to the first wait. */
    data class Wrong(val attemptsLeft: Int) : UnlockResult

    /** The attempt was refused because a wait is still running. */
    data class Waiting(val remainingMs: Long) : UnlockResult
}

/** Whether the passcode of this device is set, and whether the app is open. */
enum class PasscodeStatus {

    /** No passcode has been set. */
    NOT_SET,

    /** A passcode is set and it has to be entered. */
    LOCKED,

    /** A passcode is set and the app is inside the grace period. */
    OPEN
}

/**
 * The passcode that protects this device.
 *
 * Stretching a passcode costs real time, so every check runs off the main thread and the screen
 * keeps responding while it happens. The attempt counter and the waits live in [LockGuard], which
 * is pure and tested; this class only adds storage, a clock and a flow the screens can watch.
 */
class PasscodeRepository(
    private val store: PasscodeStore
) {

    private var passcode: StoredPasscode = store.load()

    private var autoLockDelay: AutoLockDelay = store.loadAutoLockDelay()

    private val _status = MutableStateFlow(statusOf(passcode.state, now()))

    /** What the screens show. */
    val status: StateFlow<PasscodeStatus> = _status.asStateFlow()

    private val _isWorking = MutableStateFlow(false)

    /** True while a passcode is being checked, so the screen can disable its button. */
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    /** How long one unlock lasts. */
    val delay: AutoLockDelay get() = autoLockDelay

    /** True when a passcode protects this device. */
    fun isConfigured(): Boolean = passcode.isConfigured

    /** True when the app should be showing the lock screen right now. */
    fun needsPasscodeNow(): Boolean =
        passcode.isConfigured && LockGuard.needsPasscode(passcode.state, now())

    /** Sets the first passcode. [confirmation] must repeat it. */
    suspend fun set(passcodeText: String, confirmation: String): PasscodeSetResult =
        change(current = null, new = passcodeText, confirmation = confirmation)

    /** Replaces the passcode. [current] must be the one already in use. */
    suspend fun change(current: String?, new: String, confirmation: String): PasscodeSetResult {
        if (current != null && !verify(current)) return PasscodeSetResult.CurrentPasscodeWrong
        if (new != confirmation) return PasscodeSetResult.ConfirmationMismatch
        val check = PasscodePolicy.check(new, if (passcode.isConfigured) current else null)
        if (check != PasscodeCheck.Accepted) return PasscodeSetResult.Refused(check)

        val salt = PasscodeHash.newSalt()
        val hash = withStretch { PasscodeHash.create(new, salt) }
        val state = LockGuard.afterSuccess(
            passcode.state.copy(autoLockDelayMs = autoLockDelay.durationMs),
            now(),
            autoLockDelay
        )
        passcode = StoredPasscode(salt, hash, state)
        store.save(passcode)
        publish()
        return PasscodeSetResult.Set
    }

    /** Removes the passcode. Telegram's own session is not touched. */
    fun clear() {
        store.clear()
        passcode = StoredPasscode.NONE
        publish()
    }

    /** Checks a passcode at the lock screen, and records the attempt either way. */
    suspend fun unlock(attempt: String): UnlockResult {
        if (!passcode.isConfigured) return UnlockResult.Opened
        val state = passcode.state
        if (state.isLocked(now())) {
            return UnlockResult.Waiting(state.remainingLockMs(now()))
        }
        if (attempt.isEmpty()) return UnlockResult.Wrong(LockGuard.FREE_ATTEMPTS - state.attempts)

        _isWorking.value = true
        val correct = try {
            verify(attempt)
        } finally {
            _isWorking.value = false
        }

        val nowMs = now()
        passcode = if (correct) {
            passcode.copy(state = LockGuard.afterSuccess(state, nowMs, autoLockDelay))
        } else {
            passcode.copy(state = LockGuard.afterFailure(state, nowMs))
        }
        store.saveState(passcode.state)
        publish()
        return when {
            correct -> UnlockResult.Opened
            passcode.state.isLocked(nowMs) -> UnlockResult.Waiting(passcode.state.remainingLockMs(nowMs))
            else -> UnlockResult.Wrong(LockGuard.FREE_ATTEMPTS - passcode.state.attempts)
        }
    }

    /** How long one unlock lasts. */
    fun setDelay(delay: AutoLockDelay) {
        autoLockDelay = delay
        store.saveAutoLockDelay(delay)
        passcode = passcode.copy(state = LockGuard.withDelay(passcode.state, delay, now()))
        if (passcode.isConfigured) store.saveState(passcode.state)
        publish()
    }

    /**
     * Called when the app returns to the screen.
     *
     * Inside the grace period nothing happens. Outside it the status goes back to locked, which
     * is what makes the gate in front of the chat list close again.
     */
    fun refresh() = publish()

    private suspend fun verify(attempt: String): Boolean {
        val salt = passcode.salt
        val hash = passcode.hash
        return withStretch { PasscodeHash.verify(attempt, salt, hash) }
    }

    /** Runs the slow part off the main thread. */
    private suspend fun <T> withStretch(block: () -> T): T = withContext(Dispatchers.Default) { block() }

    private fun statusOf(state: LockState, nowMs: Long): PasscodeStatus = when {
        !passcode.isConfigured -> PasscodeStatus.NOT_SET
        LockGuard.needsPasscode(state, nowMs) -> PasscodeStatus.LOCKED
        else -> PasscodeStatus.OPEN
    }

    private fun publish() {
        _status.value = statusOf(passcode.state, now())
    }

    private fun now(): Long = System.currentTimeMillis()
}
