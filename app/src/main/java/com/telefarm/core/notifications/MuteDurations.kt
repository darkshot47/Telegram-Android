package com.telefarm.core.notifications

/** How long a chat can be muted for. */
enum class MuteDuration(val seconds: Int) {

    ONE_HOUR(3_600),

    EIGHT_HOURS(8 * 3_600),

    TWO_DAYS(2 * 86_400),

    /**
     * No end in sight.
     *
     * Telegram holds a mute as a count of seconds and has no value for "never unmute", so this is
     * the largest count it will take - about 68 years. It is called forever because that is what
     * it means to the person choosing it, not because the number is infinite.
     */
    FOREVER(Int.MAX_VALUE)
}

/** How much of a mute is left, in the largest unit that is still honest. */
sealed interface MuteRemaining {

    /** The chat is not muted. */
    object NotMuted : MuteRemaining

    /** Muted for longer than anything worth counting. */
    object Forever : MuteRemaining

    data class Minutes(val count: Int) : MuteRemaining

    data class Hours(val count: Int) : MuteRemaining

    data class Days(val count: Int) : MuteRemaining
}

/**
 * Muting a chat for a while.
 *
 * A mute that can only be "on" is a mute nobody turns off, because the only way to hear the chat
 * again is to unmute it by hand and remember to. Offering an hour, an evening, a weekend and
 * forever covers the reasons people mute: a conversation that is loud right now, one that is loud
 * today, one that is loud this week, and one they never want to hear from.
 *
 * Telegram holds the mute as seconds left, so what is read back is a countdown rather than the
 * choice that was made. Both directions are here, and the countdown rounds up: a mute with forty
 * seconds left is "1 minute" and never "0 minutes", because a chat that says it is muted for no
 * time at all is a chat that looks like it is not muted.
 */
object MuteDurations {

    /** The value that means not muted. */
    const val OFF = 0

    /** A mute at least this long is read as forever rather than as a number of days. */
    val FOREVER_THRESHOLD = 365 * 86_400

    /** The durations on offer, shortest first. */
    val CHOICES = MuteDuration.entries.toList()

    /** True when the chat is muted at all. */
    fun isMuted(muteForSeconds: Int): Boolean = muteForSeconds > OFF

    /** Which offered duration a mute that is already set corresponds to, or null if it is none. */
    fun offered(muteForSeconds: Int): MuteDuration? =
        MuteDuration.entries.firstOrNull { it.seconds == muteForSeconds }

    /**
     * How the time left on a mute reads, in the largest unit that is still honest.
     *
     * The unit is chosen by the count it would produce rather than by the number of seconds, so
     * an exact hour reads as "1 hour" and not as "60 minutes". That matters because rounding up
     * can fill a whole of the next unit - 59 minutes 59 seconds is 60 minutes - and a mute that
     * reads as 60 minutes when it reads better as an hour is a small lie told sixty times a day.
     */
    fun remaining(muteForSeconds: Int): MuteRemaining {
        if (muteForSeconds <= OFF) return MuteRemaining.NotMuted
        if (muteForSeconds >= FOREVER_THRESHOLD) return MuteRemaining.Forever

        val minutes = roundUp(muteForSeconds, SECONDS_PER_MINUTE)
        if (minutes < MINUTES_PER_HOUR) return MuteRemaining.Minutes(minutes)

        val hours = roundUp(muteForSeconds, SECONDS_PER_HOUR)
        if (hours < HOURS_PER_DAY) return MuteRemaining.Hours(hours)

        return MuteRemaining.Days(roundUp(muteForSeconds, SECONDS_PER_DAY))
    }

    private const val SECONDS_PER_MINUTE = 60
    private const val SECONDS_PER_HOUR = 3_600
    private const val SECONDS_PER_DAY = 86_400
    private const val MINUTES_PER_HOUR = 60
    private const val HOURS_PER_DAY = 24

    /**
     * Whole units, counting a part unit as a whole one.
     *
     * Rounding up is the only rounding that cannot produce zero: a mute that is still on always
     * reads as at least one unit of something.
     */
    private fun roundUp(seconds: Int, unit: Int): Int = (seconds + unit - 1) / unit
}
