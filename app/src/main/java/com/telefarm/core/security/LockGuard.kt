package com.telefarm.core.security

/**
 * How long the app stays unlocked, and how long a wrong passcode costs.
 *
 * Both are decisions about time, so they are kept here as pure functions on a value that is
 * stored as text. Nothing in this file knows about the clock: the caller passes the time in, and
 * the rules can be tested against any moment.
 */
data class LockState(

    /** Wrong guesses since the last success. */
    val attempts: Int = 0,

    /** Milliseconds since the epoch before which no guess is accepted; 0 when open. */
    val lockedUntil: Long = 0,

    /** Milliseconds since the epoch until which the app needs no passcode. */
    val unlockedUntil: Long = 0,

    /** How long one unlock lasts. [AutoLockDelay.durationMs]; negative locks on every stop. */
    val autoLockDelayMs: Long = 0
) {

    /** True while a wrong passcode is still costing the user a wait. */
    fun isLocked(nowMs: Long): Boolean = nowMs < lockedUntil

    /** True while the app still needs no passcode. */
    fun isUnlocked(nowMs: Long): Boolean = nowMs < unlockedUntil

    /** Milliseconds left of the wait after a wrong passcode. */
    fun remainingLockMs(nowMs: Long): Long = (lockedUntil - nowMs).coerceAtLeast(0)

    companion object {

        /** State of an app that has never been locked. */
        val OPEN = LockState()

        private const val SEPARATOR = '|'

        /** Stored form: `attempts|lockedUntil|unlockedUntil|autoLockDelayMs`. */
        fun encode(state: LockState): String = listOf(
            state.attempts,
            state.lockedUntil,
            state.unlockedUntil,
            state.autoLockDelayMs
        ).joinToString(SEPARATOR.toString())

        /** [encode] reversed. Text that is not a stored state reads as the open state. */
        fun decode(text: String?): LockState {
            if (text.isNullOrBlank()) return OPEN
            val parts = text.split(SEPARATOR)
            if (parts.size != 4) return OPEN
            val numbers = parts.map { it.toLongOrNull() ?: return OPEN }
            return LockState(
                attempts = numbers[0].toInt().coerceAtLeast(0),
                lockedUntil = numbers[1].coerceAtLeast(0),
                unlockedUntil = numbers[2].coerceAtLeast(0),
                autoLockDelayMs = numbers[3]
            )
        }
    }
}

/** How long one successful unlock lasts. */
enum class AutoLockDelay(val durationMs: Long) {

    /** Lock as soon as the app leaves the screen. */
    IMMEDIATELY(0),

    /** A minute, for the case of a quick look away. */
    ONE_MINUTE(60_000),

    /** Five minutes. */
    FIVE_MINUTES(300_000),

    /** Fifteen minutes. */
    FIFTEEN_MINUTES(900_000),

    /** An hour. */
    ONE_HOUR(3_600_000);

    companion object {

        /** The delay stored as [LockState.autoLockDelayMs]; unknown values read as [IMMEDIATELY]. */
        fun of(durationMs: Long): AutoLockDelay = entries.firstOrNull { it.durationMs == durationMs } ?: IMMEDIATELY
    }
}

/**
 * The rules that turn a passcode attempt into the next [LockState].
 *
 * A wrong guess costs nothing for the first few tries, then costs more each time: enough that
 * guessing four digits a hundred times a second stops being possible, and not so much that one
 * fat finger makes the app unusable for the day.
 */
object LockGuard {

    /** Wrong guesses allowed before the first wait. */
    const val FREE_ATTEMPTS = 5

    /** Longest wait a wrong guess can cost. */
    const val MAX_LOCK_MS = 3_600_000L

    /**
     * Shortest grace period a successful unlock gives, whatever delay is set.
     *
     * The gate in front of the chat list runs when the app comes back to the screen. A delay of
     * zero would make that gate close again at the same moment the lock screen closes, and the
     * app would put the lock screen straight back up. A few seconds is enough to survive the
     * transition and short enough that leaving the app still locks it.
     */
    const val MINIMUM_GRACE_MS = 3_000L

    /** Waits after the [FREE_ATTEMPTS]th, 6th, 7th ... wrong guess, in seconds. */
    private val BACKOFF_SECONDS = longArrayOf(30, 60, 300, 900, 3_600)

    /** State after a wrong passcode: one more attempt, and a wait once they run out. */
    fun afterFailure(state: LockState, nowMs: Long): LockState {
        val attempts = state.attempts + 1
        val waitMs = backoffMs(attempts)
        return state.copy(
            attempts = attempts,
            lockedUntil = if (waitMs == 0L) 0 else nowMs + waitMs,
            unlockedUntil = 0
        )
    }

    /** State after the right passcode: no attempts counted, and one grace period of [delay]. */
    fun afterSuccess(state: LockState, nowMs: Long, delay: AutoLockDelay = AutoLockDelay.of(state.autoLockDelayMs)): LockState =
        LockState(
            attempts = 0,
            lockedUntil = 0,
            unlockedUntil = graceUntil(nowMs, delay),
            autoLockDelayMs = delay.durationMs
        )

    /** Same state with a different grace period, keeping a wait that is still running. */
    fun withDelay(state: LockState, delay: AutoLockDelay, nowMs: Long): LockState {
        val grace = if (state.isUnlocked(nowMs)) graceUntil(nowMs, delay) else state.unlockedUntil
        return state.copy(autoLockDelayMs = delay.durationMs, unlockedUntil = grace)
    }

    /** The moment a grace period that starts at [nowMs] ends. */
    private fun graceUntil(nowMs: Long, delay: AutoLockDelay): Long =
        nowMs + maxOf(delay.durationMs, MINIMUM_GRACE_MS)

    /** Milliseconds a wrong guess at [attempts] wrong guesses costs. */
    fun backoffMs(attempts: Int): Long {
        if (attempts <= FREE_ATTEMPTS) return 0
        val index = (attempts - FREE_ATTEMPTS - 1).coerceIn(0, BACKOFF_SECONDS.lastIndex)
        return (BACKOFF_SECONDS[index] * 1000).coerceAtMost(MAX_LOCK_MS)
    }

    /** True when the app should ask for the passcode at [nowMs]. */
    fun needsPasscode(state: LockState, nowMs: Long): Boolean =
        state.isLocked(nowMs) || !state.isUnlocked(nowMs)
}
