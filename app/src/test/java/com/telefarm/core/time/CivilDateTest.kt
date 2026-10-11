package com.telefarm.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Seconds since the epoch into a date and a clock.
 *
 * The arithmetic is checked against `java.time`, which is a different implementation of the same
 * calendar, over a sweep that crosses leap years, century rules and the epoch in both directions.
 * Checking one implementation against another is worth more here than a handful of picked dates,
 * because the failure this guards against is a transcript that reads a day out.
 */
class CivilDateTest {

    @Test
    fun `the epoch reads as the first of January 1970`() {
        val moment = CivilDate.fromEpochSeconds(0)
        assertEquals(1970, moment.year)
        assertEquals(1, moment.month)
        assertEquals(1, moment.day)
        assertEquals(0, moment.hour)
        assertEquals(0, moment.minute)
        assertEquals(0L, moment.epochDay)
    }

    @Test
    fun `a moment before the epoch belongs to the day before`() {
        val moment = CivilDate.fromEpochSeconds(-1)
        assertEquals("1969-12-31", moment.isoDate)
        assertEquals("23:59", moment.clock)
        assertEquals(-1L, moment.epochDay)
    }

    @Test
    fun `the offset moves the clock and can move the day`() {
        assertEquals("05:30", CivilDate.fromEpochSeconds(0, 330).clock)
        assertEquals("1970-01-01", CivilDate.fromEpochSeconds(0, 330).isoDate)
        // 23:00 UTC is the next morning in India.
        assertEquals("1970-01-02", CivilDate.fromEpochSeconds(23 * 3600, 330).isoDate)
        assertEquals("04:30", CivilDate.fromEpochSeconds(23 * 3600, 330).clock)
        // And the previous evening in California.
        assertEquals("1969-12-31", CivilDate.fromEpochSeconds(3600, -480).isoDate)
    }

    @Test
    fun `a leap day is a real day`() {
        // 2000-02-29 00:00 UTC. 2000 is a leap year because 400 divides it.
        val moment = CivilDate.fromEpochSeconds(951_782_400)
        assertEquals("2000-02-29", moment.isoDate)
        assertEquals(29, moment.day)
        assertEquals(2, moment.month)
    }

    @Test
    fun `a century year that is not a leap year has no 29 February`() {
        // 1900-03-01 00:00 UTC; the day before it is 28 February, not 29.
        val moment = CivilDate.fromEpochSeconds(-2_203_891_200)
        assertEquals("1900-03-01", moment.isoDate)
        assertEquals("1900-02-28", CivilDate.fromEpochSeconds(-2_203_891_200 - 86_400).isoDate)
    }

    @Test
    fun `the arithmetic agrees with java time over a long sweep`() {
        var checked = 0
        var second = -3_000_000_000L
        while (second < 4_000_000_000L) {
            for (offsetMinutes in intArrayOf(0, 330, -480, 840, -720)) {
                val ours = CivilDate.fromEpochSeconds(second, offsetMinutes)
                val theirs = Instant.ofEpochSecond(second).atZone(ZoneOffset.ofTotalSeconds(offsetMinutes * 60))
                assertEquals("second $second offset $offsetMinutes", theirs.year, ours.year)
                assertEquals("second $second offset $offsetMinutes", theirs.monthValue, ours.month)
                assertEquals("second $second offset $offsetMinutes", theirs.dayOfMonth, ours.day)
                assertEquals("second $second offset $offsetMinutes", theirs.hour, ours.hour)
                assertEquals("second $second offset $offsetMinutes", theirs.minute, ours.minute)
                checked++
            }
            second += 104_729L
        }
        assertTrue("the sweep should have covered a lot", checked > 100_000)
    }

    @Test
    fun `a clock reads with a leading zero`() {
        assertEquals("00:00", CivilDate.fromEpochSeconds(0).clock)
        assertEquals("09:05", CivilDate.fromEpochSeconds(9 * 3600 + 5 * 60).clock)
        assertEquals("23:59", CivilDate.fromEpochSeconds(86_399).clock)
    }

    @Test
    fun `a date reads as a long date`() {
        assertEquals("1 January 1970", CivilDate.fromEpochSeconds(0).longDate)
        assertEquals("29 February 2000", CivilDate.fromEpochSeconds(951_782_400).longDate)
        assertEquals("31 December 2026", CivilDate.fromEpochSeconds(1_798_761_599).longDate)
    }

    @Test
    fun `the day count groups a day and splits the next`() {
        assertTrue(CivilDate.isSameDay(0, 86_399))
        assertFalse(CivilDate.isSameDay(0, 86_400))
        // 19:00 UTC is still the same day in UTC but already the next morning in India.
        assertTrue(CivilDate.isSameDay(0, 19 * 3600, 0))
        assertFalse(CivilDate.isSameDay(0, 19 * 3600, 330))
    }

    @Test
    fun `the day count is the same as the one on the reading`() {
        for (second in listOf(-100_000L, 0L, 1_760_000_000L, 4_000_000_000L)) {
            assertEquals(
                CivilDate.epochDayOf(second, 330),
                CivilDate.fromEpochSeconds(second, 330).epochDay
            )
        }
    }

    @Test
    fun `an iso date sorts the way it reads`() {
        val earlier = CivilDate.fromEpochSeconds(1_760_000_000).isoDate
        val later = CivilDate.fromEpochSeconds(1_760_000_000 + 400 * 86_400).isoDate
        assertTrue("$earlier should sort before $later", earlier < later)
    }
}
