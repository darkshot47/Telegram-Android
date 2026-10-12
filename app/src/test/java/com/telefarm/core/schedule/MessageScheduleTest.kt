package com.telefarm.core.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scheduling a message for later. */
class MessageScheduleTest {

    /** 2026-01-01 12:00:00 UTC. */
    private val noon = 1_767_268_800L

    /** IST, the default zone of the app, in minutes from UTC as [CivilDate] takes it. */
    private val ist = 5 * 60 + 30

    @Test
    fun `a moment an hour ahead can be used`() {
        assertEquals(
            ScheduleDecision.Ok,
            MessageSchedule.validate(noon + 3600, noon)
        )
    }

    @Test
    fun `a moment already past cannot`() {
        assertTrue(MessageSchedule.validate(noon - 1, noon) is ScheduleDecision.TooSoon)
    }

    @Test
    fun `a moment now is a message to send now, not a schedule`() {
        assertTrue(MessageSchedule.validate(noon, noon) is ScheduleDecision.TooSoon)
    }

    @Test
    fun `half a minute away is too soon`() {
        assertTrue(MessageSchedule.validate(noon + 30, noon) is ScheduleDecision.TooSoon)
    }

    @Test
    fun `exactly the shortest lead is allowed`() {
        assertEquals(
            ScheduleDecision.Ok,
            MessageSchedule.validate(noon + MessageSchedule.MINIMUM_LEAD_SECONDS, noon)
        )
    }

    @Test
    fun `a year away is the furthest a message is held`() {
        assertEquals(
            ScheduleDecision.Ok,
            MessageSchedule.validate(noon + MessageSchedule.MAXIMUM_LEAD_SECONDS, noon)
        )
    }

    @Test
    fun `a year and a second away is too far`() {
        val decision = MessageSchedule.validate(noon + MessageSchedule.MAXIMUM_LEAD_SECONDS + 1, noon)
        assertTrue(decision is ScheduleDecision.TooFar)
        assertEquals(
            MessageSchedule.MAXIMUM_LEAD_SECONDS,
            (decision as ScheduleDecision.TooFar).longestLeadSeconds
        )
    }

    @Test
    fun `the shortest lead is reported back so it can be shown`() {
        val decision = MessageSchedule.validate(noon, noon) as ScheduleDecision.TooSoon
        assertEquals(MessageSchedule.MINIMUM_LEAD_SECONDS, decision.shortestLeadSeconds)
    }

    @Test
    fun `a moment later today is called today, in the local time zone`() {
        // 2026-01-01 12:00 UTC is 17:30 in IST, and 14:00 UTC is 19:30 the same evening there.
        assertEquals("Today 19:30", MessageSchedule.label(noon + 7200, noon, ist))
    }

    @Test
    fun `a moment tomorrow is called tomorrow`() {
        assertEquals("Tomorrow 17:30", MessageSchedule.label(noon + 86_400, noon, ist))
    }

    @Test
    fun `a moment further out is called by its day of the week`() {
        assertEquals("Sunday 17:30", MessageSchedule.label(noon + 3 * 86_400, noon, ist))
    }

    @Test
    fun `a moment over a week out is called by its date`() {
        assertEquals("January 11 17:30", MessageSchedule.label(noon + 10 * 86_400, noon, ist))
    }

    @Test
    fun `the same moment is a different day in a different time zone`() {
        // 2026-01-01 22:00 UTC is already the next morning in IST.
        val late = 1_767_304_800L
        assertEquals("Today 03:30", MessageSchedule.label(late, late - 3600, ist))
        assertEquals("Tomorrow 03:30", MessageSchedule.label(late + 86_400, late, ist))
    }
}
