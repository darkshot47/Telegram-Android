package com.telefarm.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Muting a chat for a while rather than for good. */
class MuteDurationsTest {

    @Test
    fun `zero seconds is not muted`() {
        assertFalse(MuteDurations.isMuted(0))
        assertEquals(MuteRemaining.NotMuted, MuteDurations.remaining(0))
    }

    @Test
    fun `a negative count is not muted either`() {
        assertFalse(MuteDurations.isMuted(-5))
        assertEquals(MuteRemaining.NotMuted, MuteDurations.remaining(-5))
    }

    @Test
    fun `one second left is a minute, never no time at all`() {
        assertEquals(MuteRemaining.Minutes(1), MuteDurations.remaining(1))
    }

    @Test
    fun `a part of a minute counts as a whole one`() {
        assertEquals(MuteRemaining.Minutes(1), MuteDurations.remaining(59))
    }

    @Test
    fun `exactly a minute is one minute`() {
        assertEquals(MuteRemaining.Minutes(1), MuteDurations.remaining(60))
    }

    @Test
    fun `a minute and a second is two minutes`() {
        assertEquals(MuteRemaining.Minutes(2), MuteDurations.remaining(61))
    }

    @Test
    fun `an hour is read in hours, not in sixty minutes`() {
        assertEquals(MuteRemaining.Hours(1), MuteDurations.remaining(3_600))
    }

    @Test
    fun `an hour and a second is two hours`() {
        assertEquals(MuteRemaining.Hours(2), MuteDurations.remaining(3_601))
    }

    @Test
    fun `a day is read in days, not in twenty-four hours`() {
        assertEquals(MuteRemaining.Days(1), MuteDurations.remaining(86_400))
    }

    @Test
    fun `a month is read in days`() {
        assertEquals(MuteRemaining.Days(30), MuteDurations.remaining(30 * 86_400))
    }

    @Test
    fun `a year or more is forever rather than a number of days`() {
        assertEquals(MuteRemaining.Forever, MuteDurations.remaining(MuteDurations.FOREVER_THRESHOLD))
        assertEquals(MuteRemaining.Forever, MuteDurations.remaining(Int.MAX_VALUE))
    }

    @Test
    fun `a day short of a year is still counted`() {
        val seconds = MuteDurations.FOREVER_THRESHOLD - 1
        assertEquals(MuteRemaining.Days(365), MuteDurations.remaining(seconds))
    }

    @Test
    fun `every duration on offer reads back as itself`() {
        for (duration in MuteDurations.CHOICES) {
            assertEquals(duration, MuteDurations.offered(duration.seconds))
            assertTrue(MuteDurations.isMuted(duration.seconds))
        }
    }

    @Test
    fun `a mute nobody offered reads back as none of them`() {
        assertNull(MuteDurations.offered(1_234))
        assertNull(MuteDurations.offered(0))
    }

    @Test
    fun `the choices run shortest first`() {
        val seconds = MuteDurations.CHOICES.map { it.seconds }
        assertEquals(seconds.sorted(), seconds)
    }

    @Test
    fun `forever is the largest count an int32 holds`() {
        assertEquals(Int.MAX_VALUE, MuteDuration.FOREVER.seconds)
        assertEquals(MuteDurations.CHOICES.last(), MuteDuration.FOREVER)
    }

    @Test
    fun `no choice is a mute that has already run out`() {
        assertTrue(MuteDurations.CHOICES.all { it.seconds > MuteDurations.OFF })
    }
}
