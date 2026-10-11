package com.telefarm.core.notifications

/**
 * The hours of the day when an incoming message makes no sound and shows no banner.
 *
 * Times are minutes after midnight, which is what a clock picker hands over and what the whole
 * file works in, so nothing here knows about time zones or about the current time: the caller
 * passes the minute of the day in.
 *
 * A range may cross midnight - the usual case, from late evening to morning - and is read that
 * way rather than as a range that never happens.
 */
data class QuietHours(

    /** Whether quiet hours are on at all. */
    val enabled: Boolean = false,

    /** Minute of the day the quiet starts. */
    val startMinuteOfDay: Int = DEFAULT_START,

    /** Minute of the day the quiet ends. */
    val endMinuteOfDay: Int = DEFAULT_END,

    /** Whether a message that mentions you is still allowed through. */
    val allowsMentions: Boolean = true
) {

    /** True when the range runs past midnight into the next day. */
    val spansMidnight: Boolean get() = startMinuteOfDay > endMinuteOfDay

    /**
     * True at [minuteOfDay].
     *
     * A range whose two ends are the same minute is read as no quiet hours rather than as a whole
     * silent day: that is what someone setting both ends to 08:00 means, and the other reading
     * would silence the app with no way to see why.
     */
    fun isQuiet(minuteOfDay: Int): Boolean = when {
        !enabled -> false
        startMinuteOfDay == endMinuteOfDay -> false
        spansMidnight -> minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay
        else -> minuteOfDay >= startMinuteOfDay && minuteOfDay < endMinuteOfDay
    }

    /** Minutes of quiet in a day, which is what the summary of the setting says. */
    val quietMinutesPerDay: Int
        get() = when {
            !enabled || startMinuteOfDay == endMinuteOfDay -> 0
            spansMidnight -> MINUTES_PER_DAY - startMinuteOfDay + endMinuteOfDay
            else -> endMinuteOfDay - startMinuteOfDay
        }

    companion object {

        const val MINUTES_PER_DAY = 24 * 60

        /** 23:00. */
        const val DEFAULT_START = 23 * 60

        /** 07:00. */
        const val DEFAULT_END = 7 * 60

        private const val SEPARATOR = '|'

        /** Minute of the day of [hour]:[minute], wrapped into one day. */
        fun minuteOfDay(hour: Int, minute: Int): Int =
            ((hour % 24) + 24) % 24 * 60 + ((minute % 60) + 60) % 60

        /** A minute of the day as `23:05`. */
        fun format(minuteOfDay: Int): String {
            val bounded = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
            val hour = bounded / 60
            val minute = bounded % 60
            return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
        }

        /** Stored form: `enabled|start|end|allowsMentions`. */
        fun encode(hours: QuietHours): String = listOf(
            if (hours.enabled) 1 else 0,
            hours.startMinuteOfDay,
            hours.endMinuteOfDay,
            if (hours.allowsMentions) 1 else 0
        ).joinToString(SEPARATOR.toString())

        /** [encode] reversed. Text that is not a stored setting reads as the defaults. */
        fun decode(text: String?): QuietHours {
            if (text.isNullOrBlank()) return QuietHours()
            val parts = text.split(SEPARATOR)
            if (parts.size != 4) return QuietHours()
            val numbers = parts.map { it.toIntOrNull() ?: return QuietHours() }
            return QuietHours(
                enabled = numbers[0] != 0,
                startMinuteOfDay = numbers[1].coerceIn(0, MINUTES_PER_DAY - 1),
                endMinuteOfDay = numbers[2].coerceIn(0, MINUTES_PER_DAY - 1),
                allowsMentions = numbers[3] != 0
            )
        }
    }
}

/**
 * Whether a message arriving now should stay silent.
 *
 * Kept apart from [QuietHours] because the two questions are different: what the setting says,
 * and what to do with one message. A mention is let through when the setting allows it, because
 * a message that names you is the one case where silence costs something.
 */
object NotificationPolicy {

    /** True when a message arriving at [minuteOfDay] makes no sound and shows no banner. */
    fun suppresses(hours: QuietHours, minuteOfDay: Int, isMention: Boolean): Boolean =
        hours.isQuiet(minuteOfDay) && !(hours.allowsMentions && isMention)
}
