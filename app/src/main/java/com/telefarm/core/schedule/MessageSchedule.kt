package com.telefarm.core.schedule

import com.telefarm.core.time.CivilDate

/** Whether a moment chosen for a message can actually be used. */
sealed interface ScheduleDecision {

    /** The message will be sent then. */
    object Ok : ScheduleDecision

    /**
     * The moment is in the past, or too close to now to be worth scheduling.
     *
     * @param shortestLeadSeconds how far ahead it has to be.
     */
    data class TooSoon(val shortestLeadSeconds: Long) : ScheduleDecision

    /**
     * The moment is further ahead than a message is held.
     *
     * @param longestLeadSeconds how far ahead a message can be held.
     */
    data class TooFar(val longestLeadSeconds: Long) : ScheduleDecision
}

/**
 * Sending a message later.
 *
 * A scheduled message waits on Telegram, not on this device, so it goes out even if the phone is
 * off when the moment comes. That is what makes it worth having, and it is also why the moment has
 * to be a real one: something already past is not a schedule, and something a year away is beyond
 * what will be held.
 *
 * Both limits are checked here rather than left to a refusal later, so the person is told before
 * they write the message what will not work.
 */
object MessageSchedule {

    /** A message less than this far ahead is sent now rather than scheduled. */
    const val MINIMUM_LEAD_SECONDS = 60L

    /** How far ahead a message is held: a year. */
    const val MAXIMUM_LEAD_SECONDS = 365L * CivilDate.SECONDS_PER_DAY

    /** Whether the chosen moment can be used. */
    fun validate(targetSeconds: Long, nowSeconds: Long): ScheduleDecision {
        val lead = targetSeconds - nowSeconds
        return when {
            lead < MINIMUM_LEAD_SECONDS -> ScheduleDecision.TooSoon(MINIMUM_LEAD_SECONDS)
            lead > MAXIMUM_LEAD_SECONDS -> ScheduleDecision.TooFar(MAXIMUM_LEAD_SECONDS)
            else -> ScheduleDecision.Ok
        }
    }

    /**
     * What the chosen moment is called, in the words of the day it falls on.
     *
     * "Tonight at 23:00" says more than a timestamp, and the time zone is passed in because a
     * schedule only means something in the zone the person is in, which this file does not look
     * up any more than the calendar arithmetic behind it does.
     *
     * @param utcOffsetMinutes the zone to read the moment in.
     */
    fun label(targetSeconds: Long, nowSeconds: Long, utcOffsetMinutes: Int = 0): String {
        val target = CivilDate.fromEpochSeconds(targetSeconds, utcOffsetMinutes)
        val days = target.epochDay - CivilDate.epochDayOf(nowSeconds, utcOffsetMinutes)
        val day = when (days) {
            0L -> TODAY
            1L -> TOMORROW
            // Naming the day works only while it is still this week; past that the date does.
            in 2..6 -> DAY_NAMES[dayOfWeek(target.epochDay)]
            else -> "${MONTH_NAMES[target.month - 1]} ${target.day}"
        }
        return "$day ${target.clock}"
    }

    /**
     * Day of the week of a day count since the epoch, 0 for Monday.
     *
     * 1970-01-01 was a Thursday, so the count is shifted to put Monday at zero.
     */
    private fun dayOfWeek(epochDay: Long): Int = Math.floorMod(epochDay + 3, 7).toInt()

    private val DAY_NAMES = listOf(
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
    )

    private val MONTH_NAMES = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )

    private const val TODAY = "Today"
    private const val TOMORROW = "Tomorrow"
}
