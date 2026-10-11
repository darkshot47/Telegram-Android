package com.telefarm.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hours when an incoming message stays silent. */
class QuietHoursTest {

    private val overnight = QuietHours(enabled = true)

    @Test
    fun `quiet hours that are off are never quiet`() {
        val off = QuietHours(enabled = false)
        for (minute in 0 until QuietHours.MINUTES_PER_DAY step 37) {
            assertFalse("minute $minute", off.isQuiet(minute))
        }
    }

    @Test
    fun `a range across midnight is quiet on both sides of it`() {
        // 23:00 to 07:00, the default.
        assertTrue(overnight.isQuiet(at(23, 0)))
        assertTrue(overnight.isQuiet(at(23, 59)))
        assertTrue(overnight.isQuiet(at(0, 0)))
        assertTrue(overnight.isQuiet(at(3, 30)))
        assertTrue(overnight.isQuiet(at(6, 59)))
    }

    @Test
    fun `a range across midnight ends where it says`() {
        assertFalse(overnight.isQuiet(at(7, 0)))
        assertFalse(overnight.isQuiet(at(12, 0)))
        assertFalse(overnight.isQuiet(at(22, 59)))
    }

    @Test
    fun `a range inside one day`() {
        val lunch = QuietHours(enabled = true, startMinuteOfDay = at(12, 0), endMinuteOfDay = at(14, 0))
        assertFalse(lunch.spansMidnight)
        assertFalse(lunch.isQuiet(at(11, 59)))
        assertTrue(lunch.isQuiet(at(12, 0)))
        assertTrue(lunch.isQuiet(at(13, 59)))
        assertFalse(lunch.isQuiet(at(14, 0)))
        assertFalse(lunch.isQuiet(at(23, 30)))
    }

    @Test
    fun `a range of one minute is quiet for one minute`() {
        val one = QuietHours(enabled = true, startMinuteOfDay = at(9, 0), endMinuteOfDay = at(9, 1))
        assertTrue(one.isQuiet(at(9, 0)))
        assertFalse(one.isQuiet(at(9, 1)))
    }

    @Test
    fun `both ends on the same minute means no quiet hours`() {
        // The other reading would silence the app with no way to see why.
        val same = QuietHours(enabled = true, startMinuteOfDay = at(8, 0), endMinuteOfDay = at(8, 0))
        for (minute in 0 until QuietHours.MINUTES_PER_DAY step 53) {
            assertFalse("minute $minute", same.isQuiet(minute))
        }
        assertEquals(0, same.quietMinutesPerDay)
    }

    @Test
    fun `the length of the quiet is counted across midnight`() {
        assertEquals(8 * 60, overnight.quietMinutesPerDay)
        assertEquals(
            2 * 60,
            QuietHours(enabled = true, startMinuteOfDay = at(12, 0), endMinuteOfDay = at(14, 0)).quietMinutesPerDay
        )
        assertEquals(0, QuietHours(enabled = false).quietMinutesPerDay)
    }

    @Test
    fun `a mention comes through when the setting allows it`() {
        assertTrue(NotificationPolicy.suppresses(overnight, at(2, 0), isMention = false))
        assertFalse(NotificationPolicy.suppresses(overnight, at(2, 0), isMention = true))
    }

    @Test
    fun `a mention is silenced too when the setting says so`() {
        val strict = overnight.copy(allowsMentions = false)
        assertTrue(NotificationPolicy.suppresses(strict, at(2, 0), isMention = true))
    }

    @Test
    fun `nothing is suppressed during the day`() {
        assertFalse(NotificationPolicy.suppresses(overnight, at(15, 0), isMention = false))
        assertFalse(NotificationPolicy.suppresses(overnight, at(15, 0), isMention = true))
    }

    @Test
    fun `a clock reading becomes a minute of the day`() {
        assertEquals(0, QuietHours.minuteOfDay(0, 0))
        assertEquals(59, QuietHours.minuteOfDay(0, 59))
        assertEquals(60, QuietHours.minuteOfDay(1, 0))
        assertEquals(1380, QuietHours.minuteOfDay(23, 0))
        assertEquals(1439, QuietHours.minuteOfDay(23, 59))
    }

    @Test
    fun `a reading outside one day is wrapped`() {
        assertEquals(60, QuietHours.minuteOfDay(25, 0))
        assertEquals(1380, QuietHours.minuteOfDay(-1, 0))
        assertEquals(5, QuietHours.minuteOfDay(0, 65))
    }

    @Test
    fun `a minute of the day reads back as a clock`() {
        assertEquals("00:00", QuietHours.format(0))
        assertEquals("07:05", QuietHours.format(at(7, 5)))
        assertEquals("23:59", QuietHours.format(1439))
        assertEquals("00:00", QuietHours.format(1440))
        assertEquals("01:00", QuietHours.format(-1380))
    }

    @Test
    fun `the stored form survives the round trip`() {
        for (hours in listOf(
            QuietHours(),
            overnight,
            overnight.copy(enabled = false, allowsMentions = false),
            QuietHours(enabled = true, startMinuteOfDay = 0, endMinuteOfDay = 1439, allowsMentions = false)
        )) {
            assertEquals(hours, QuietHours.decode(QuietHours.encode(hours)))
        }
    }

    @Test
    fun `text that is not a stored setting reads as the defaults`() {
        assertEquals(QuietHours(), QuietHours.decode(null))
        assertEquals(QuietHours(), QuietHours.decode(""))
        assertEquals(QuietHours(), QuietHours.decode("nonsense"))
        assertEquals(QuietHours(), QuietHours.decode("1|2|3"))
        assertEquals(QuietHours(), QuietHours.decode("1|two|3|1"))
    }

    @Test
    fun `a minute outside the day is pulled back into it`() {
        assertEquals(QuietHours.MINUTES_PER_DAY - 1, QuietHours.decode("1|5000|0|1").startMinuteOfDay)
        assertEquals(0, QuietHours.decode("1|0|-7|1").endMinuteOfDay)
    }

    private fun at(hour: Int, minute: Int): Int = QuietHours.minuteOfDay(hour, minute)
}
