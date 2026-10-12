package com.telefarm.core.security

import com.telefarm.core.list.DateBucket
import com.telefarm.core.time.CivilDate
import com.telefarm.data.model.ActiveSessionUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the list of signed-in devices is read. */
class SessionListTest {

    private val today = 20_600L

    @Test
    fun `this device comes first whatever its age`() {
        val sorted = SessionList.sort(
            listOf(
                session(1, current = false, lastActive = secondsOnDay(today)),
                session(2, current = true, lastActive = secondsOnDay(today - 400)),
                session(3, current = false, lastActive = secondsOnDay(today - 1))
            )
        )
        assertEquals(listOf(2L, 1L, 3L), sorted.map { it.id })
    }

    @Test
    fun `the rest come by how recently they were used`() {
        val sorted = SessionList.sort(
            listOf(
                session(1, lastActive = secondsOnDay(today - 30)),
                session(2, lastActive = secondsOnDay(today)),
                session(3, lastActive = secondsOnDay(today - 3))
            )
        )
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.id })
    }

    @Test
    fun `sessions used at the same moment keep a steady order`() {
        val sorted = SessionList.sort(listOf(session(7), session(3), session(5)))
        assertEquals(listOf(3L, 5L, 7L), sorted.map { it.id })
    }

    @Test
    fun `an empty list sorts to an empty list`() {
        assertEquals(emptyList<ActiveSessionUi>(), SessionList.sort(emptyList()))
    }

    @Test
    fun `the title carries the version when there is one`() {
        assertEquals("Telegram Android 11.5.3", SessionList.title(session(1, app = "Telegram Android", version = "11.5.3")))
        assertEquals("Telefarm", SessionList.title(session(1, app = "Telefarm", version = "")))
        assertEquals(SessionList.UNKNOWN_APPLICATION, SessionList.title(session(1, app = "  ", version = "")))
    }

    @Test
    fun `the subtitle does not say the same thing twice`() {
        assertEquals(
            "Pixel 8 · Android 15",
            SessionList.subtitle(session(1, device = "Pixel 8", system = "Android 15"))
        )
        // The system version already inside the device name is not repeated after it.
        assertEquals(
            "iPhone iOS 17.4",
            SessionList.subtitle(session(1, device = "iPhone iOS 17.4", system = "iOS 17.4"))
        )
        assertEquals("Pixel 8", SessionList.subtitle(session(1, device = "Pixel 8", system = "")))
        assertEquals("Android 15", SessionList.subtitle(session(1, device = "", system = "Android 15", platform = "Android 15")))
        assertEquals(SessionList.UNKNOWN_DEVICE, SessionList.subtitle(session(1, device = "", system = "", platform = "")))
    }

    @Test
    fun `the place shows what Telegram reported and nothing invented`() {
        assertEquals("Mumbai, India · 1.2.3.4", SessionList.place(session(1, location = "Mumbai, India", ip = "1.2.3.4")))
        assertEquals("Mumbai, India", SessionList.place(session(1, location = "Mumbai, India", ip = "")))
        assertEquals("1.2.3.4", SessionList.place(session(1, location = "", ip = "1.2.3.4")))
        assertEquals("", SessionList.place(session(1, location = "", ip = "")))
    }

    @Test
    fun `how long ago a session was used falls in the same buckets as the chat list`() {
        assertEquals(DateBucket.TODAY, SessionList.activity(session(1, lastActive = secondsOnDay(today)), today))
        assertEquals(DateBucket.YESTERDAY, SessionList.activity(session(1, lastActive = secondsOnDay(today - 1)), today))
        assertEquals(DateBucket.THIS_WEEK, SessionList.activity(session(1, lastActive = secondsOnDay(today - 4)), today))
        assertEquals(DateBucket.THIS_MONTH, SessionList.activity(session(1, lastActive = secondsOnDay(today - 12)), today))
        assertEquals(DateBucket.EARLIER, SessionList.activity(session(1, lastActive = secondsOnDay(today - 200)), today))
    }

    @Test
    fun `a session with no activity date is the oldest bucket`() {
        assertEquals(DateBucket.EARLIER, SessionList.activity(session(1, lastActive = 0), today))
    }

    @Test
    fun `this device cannot be signed out from here`() {
        assertFalse(SessionList.canTerminate(session(1, current = true)))
        assertTrue(SessionList.canTerminate(session(2, current = false)))
    }

    @Test
    fun `everywhere else means every session but this one`() {
        val sessions = listOf(session(1, current = true), session(2), session(3))
        assertEquals(listOf(2L, 3L), SessionList.otherSessionIds(sessions))
        assertTrue(SessionList.hasOtherSessions(sessions))
    }

    @Test
    fun `a list of only this device has nowhere else`() {
        assertFalse(SessionList.hasOtherSessions(listOf(session(1, current = true))))
        assertEquals(emptyList<Long>(), SessionList.otherSessionIds(listOf(session(1, current = true))))
        assertFalse(SessionList.hasOtherSessions(emptyList()))
    }

    @Test
    fun `the activity buckets agree with the calendar they come from`() {
        val lastActive = CivilDate.fromEpochSeconds(secondsOnDay(today - 3).toLong()).epochDay
        assertEquals(today - 3, lastActive)
    }

    private fun secondsOnDay(epochDay: Long): Int = (epochDay * 86_400L + 43_200L).toInt()

    private fun session(
        id: Long,
        current: Boolean = false,
        lastActive: Int = 0,
        app: String = "Telegram",
        version: String = "1.0",
        device: String = "Pixel",
        platform: String = "Android",
        system: String = "Android 14",
        location: String = "",
        ip: String = ""
    ) = ActiveSessionUi(
        id = id,
        isCurrent = current,
        isPasswordPending = false,
        applicationName = app,
        applicationVersion = version,
        isOfficialApplication = false,
        deviceModel = device,
        platform = platform,
        systemVersion = system,
        logInDate = 0,
        lastActiveDate = lastActive,
        ipAddress = ip,
        location = location
    )
}
