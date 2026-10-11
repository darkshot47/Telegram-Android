package com.telefarm.core.time

/** A moment as a person reads it: a date and a clock, in one time zone. */
data class CivilDateTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,

    /** Days since 1970-01-01 in the same time zone, which is what groups a day. */
    val epochDay: Long
) {

    /** Clock reading, `14:05`. */
    val clock: String
        get() = "${pad(hour)}:${pad(minute)}"

    /** Date as `2026-10-11`, which sorts the way it reads. */
    val isoDate: String
        get() = "${pad(year, 4)}-${pad(month)}-${pad(day)}"

    /** Date as `11 October 2026`. */
    val longDate: String
        get() = "$day ${MONTH_NAMES[month - 1]} $year"

    private companion object {

        val MONTH_NAMES = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )

        fun pad(value: Int, width: Int = 2): String = value.toString().padStart(width, '0')
    }
}

/**
 * Seconds since the epoch into a date and a clock, with no time zone library behind it.
 *
 * The calendar here is the one every phone already uses - the proleptic Gregorian calendar - and
 * the arithmetic is the usual days-to-civil conversion. It is written out rather than taken from
 * `java.time` so that it can run, and be tested, anywhere: a transcript written on one device has
 * to read the same way on another, and that is easier to guarantee over arithmetic than over a
 * library that asks the device what its rules are.
 *
 * The offset is passed in, not looked up. Daylight saving, historical changes and the current
 * zone of the device are all the caller's business; this file only divides.
 */
object CivilDate {

    const val SECONDS_PER_DAY = 86_400L

    /** [seconds] since the epoch, read in a zone [utcOffsetMinutes] from UTC. */
    fun fromEpochSeconds(seconds: Long, utcOffsetMinutes: Int = 0): CivilDateTime {
        val local = seconds + utcOffsetMinutes * 60L
        // Floor division: a moment before 1970 belongs to the day before, not to day zero.
        val day = Math.floorDiv(local, SECONDS_PER_DAY)
        val secondOfDay = Math.floorMod(local, SECONDS_PER_DAY)
        val civil = civilFromDays(day)
        return CivilDateTime(
            year = civil[0],
            month = civil[1],
            day = civil[2],
            hour = (secondOfDay / 3600).toInt(),
            minute = ((secondOfDay % 3600) / 60).toInt(),
            epochDay = day
        )
    }

    /** Days since 1970-01-01 of [seconds], in a zone [utcOffsetMinutes] from UTC. */
    fun epochDayOf(seconds: Long, utcOffsetMinutes: Int = 0): Long =
        Math.floorDiv(seconds + utcOffsetMinutes * 60L, SECONDS_PER_DAY)

    /** True when both moments fall on the same day in the given zone. */
    fun isSameDay(firstSeconds: Long, secondSeconds: Long, utcOffsetMinutes: Int = 0): Boolean =
        epochDayOf(firstSeconds, utcOffsetMinutes) == epochDayOf(secondSeconds, utcOffsetMinutes)

    /**
     * Year, month and day of a day count since the epoch.
     *
     * The shift by 719468 days puts the epoch at the start of a 400 year cycle, so the leap year
     * rules divide evenly instead of needing a correction for every century.
     */
    private fun civilFromDays(epochDay: Long): IntArray {
        var z = epochDay + 719_468
        val era = Math.floorDiv(z, 146_097)
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val year = yearOfEra + era * 400
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val marchMonth = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * marchMonth + 2) / 5 + 1).toInt()
        val month = (if (marchMonth < 10) marchMonth + 3 else marchMonth - 9).toInt()
        return intArrayOf((if (month <= 2) year + 1 else year).toInt(), month, day)
    }
}
