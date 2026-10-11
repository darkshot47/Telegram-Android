package com.telefarm.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules behind the lock screen. */
class LockGuardTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `a fresh state asks for the passcode`() {
        assertTrue(LockGuard.needsPasscode(LockState(), now))
        assertTrue(LockGuard.needsPasscode(LockState(autoLockDelayMs = AutoLockDelay.ONE_HOUR.durationMs), now))
    }

    @Test
    fun `the first wrong guesses cost nothing`() {
        var state = LockState()
        for (attempt in 1..LockGuard.FREE_ATTEMPTS) {
            state = LockGuard.afterFailure(state, now)
            assertEquals(attempt, state.attempts)
            assertFalse("attempt $attempt should not lock", state.isLocked(now))
        }
    }

    @Test
    fun `the guess after the free ones starts a wait that grows`() {
        var state = LockState()
        repeat(LockGuard.FREE_ATTEMPTS) { state = LockGuard.afterFailure(state, now) }

        state = LockGuard.afterFailure(state, now)
        assertEquals(30_000L, LockGuard.backoffMs(state.attempts))
        assertTrue(state.isLocked(now))
        assertEquals(30_000L, state.remainingLockMs(now))

        state = LockGuard.afterFailure(state, now)
        assertEquals(60_000L, LockGuard.backoffMs(state.attempts))

        state = LockGuard.afterFailure(state, now)
        assertEquals(300_000L, LockGuard.backoffMs(state.attempts))
    }

    @Test
    fun `the wait stops growing at an hour`() {
        assertEquals(LockGuard.MAX_LOCK_MS, LockGuard.backoffMs(50))
        assertEquals(LockGuard.MAX_LOCK_MS, LockGuard.backoffMs(1_000))
    }

    @Test
    fun `a wait runs out`() {
        var state = LockState()
        repeat(LockGuard.FREE_ATTEMPTS + 1) { state = LockGuard.afterFailure(state, now) }
        assertTrue(state.isLocked(now))
        assertFalse(state.isLocked(now + 30_001))
        assertEquals(0L, state.remainingLockMs(now + 30_001))
    }

    @Test
    fun `the right passcode clears the attempts and the wait`() {
        var state = LockState()
        repeat(LockGuard.FREE_ATTEMPTS + 2) { state = LockGuard.afterFailure(state, now) }

        val open = LockGuard.afterSuccess(state, now + 60_000, AutoLockDelay.FIVE_MINUTES)
        assertEquals(0, open.attempts)
        assertFalse(open.isLocked(now + 60_000))
        assertFalse(LockGuard.needsPasscode(open, now + 60_000))
    }

    @Test
    fun `the grace period is as long as the chosen delay`() {
        val open = LockGuard.afterSuccess(LockState(), now, AutoLockDelay.ONE_MINUTE)
        assertFalse(LockGuard.needsPasscode(open, now + 59_999))
        assertTrue(LockGuard.needsPasscode(open, now + 60_000))
    }

    @Test
    fun `locking immediately still survives the return to the app`() {
        // Zero would make the gate close at the same instant the lock screen closes, and the app
        // would put the lock screen straight back up.
        val open = LockGuard.afterSuccess(LockState(), now, AutoLockDelay.IMMEDIATELY)
        assertFalse(LockGuard.needsPasscode(open, now))
        assertFalse(LockGuard.needsPasscode(open, now + LockGuard.MINIMUM_GRACE_MS - 1))
        assertTrue(LockGuard.needsPasscode(open, now + LockGuard.MINIMUM_GRACE_MS))
    }

    @Test
    fun `a delay shorter than the floor is raised to the floor`() {
        assertEquals(
            now + LockGuard.MINIMUM_GRACE_MS,
            LockGuard.afterSuccess(LockState(), now, AutoLockDelay.IMMEDIATELY).unlockedUntil
        )
    }

    @Test
    fun `changing the delay moves the grace period of an open session`() {
        val open = LockGuard.afterSuccess(LockState(), now, AutoLockDelay.ONE_MINUTE)
        val longer = LockGuard.withDelay(open, AutoLockDelay.ONE_HOUR, now)
        assertFalse(LockGuard.needsPasscode(longer, now + 60_000))
        assertEquals(AutoLockDelay.ONE_HOUR.durationMs, longer.autoLockDelayMs)
    }

    @Test
    fun `changing the delay does not end a wait that is running`() {
        var state = LockState()
        repeat(LockGuard.FREE_ATTEMPTS + 1) { state = LockGuard.afterFailure(state, now) }
        val changed = LockGuard.withDelay(state, AutoLockDelay.ONE_HOUR, now)
        assertTrue(changed.isLocked(now))
    }

    @Test
    fun `a failure clears the grace period`() {
        val open = LockGuard.afterSuccess(LockState(), now, AutoLockDelay.ONE_HOUR)
        val failed = LockGuard.afterFailure(open, now)
        assertEquals(0L, failed.unlockedUntil)
        assertTrue(LockGuard.needsPasscode(failed, now))
    }

    @Test
    fun `the stored form survives the round trip`() {
        val state = LockGuard.afterFailure(LockGuard.afterSuccess(LockState(), now, AutoLockDelay.FIFTEEN_MINUTES), now)
        assertEquals(state, LockState.decode(LockState.encode(state)))
    }

    @Test
    fun `text that is not a stored state reads as open`() {
        assertEquals(LockState.OPEN, LockState.decode(null))
        assertEquals(LockState.OPEN, LockState.decode(""))
        assertEquals(LockState.OPEN, LockState.decode("garbage"))
        assertEquals(LockState.OPEN, LockState.decode("1|2|3"))
        assertEquals(LockState.OPEN, LockState.decode("1|2|3|four"))
    }

    @Test
    fun `a negative attempt count cannot be stored`() {
        assertEquals(0, LockState.decode("-5|0|0|0").attempts)
    }

    @Test
    fun `every delay reads back from its own duration`() {
        for (delay in AutoLockDelay.entries) {
            assertEquals(delay, AutoLockDelay.of(delay.durationMs))
        }
    }

    @Test
    fun `an unknown duration reads as locking immediately`() {
        assertEquals(AutoLockDelay.IMMEDIATELY, AutoLockDelay.of(12_345))
        assertEquals(AutoLockDelay.IMMEDIATELY, AutoLockDelay.of(-1))
    }

    @Test
    fun `the delays are in order from shortest to longest`() {
        val durations = AutoLockDelay.entries.map { it.durationMs }
        assertEquals(durations.sorted(), durations)
    }
}
