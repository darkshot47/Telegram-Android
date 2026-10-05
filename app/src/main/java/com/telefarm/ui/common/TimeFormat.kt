package com.telefarm.ui.common

import android.content.Context
import com.telefarm.R
import com.telefarm.data.model.UiMessage
import com.telefarm.data.model.UserStatusUi
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Date and time presentation.
 *
 * The interesting logic (day boundaries, week boundaries) is kept in pure functions so it can
 * be unit tested without an Android device.
 */
object TimeFormat {

    private const val SECONDS_PER_DAY = 86_400L

    /** Days since the epoch for a Telegram timestamp (seconds). */
    fun epochDay(timestampSeconds: Int): Long =
        if (timestampSeconds <= 0) Long.MIN_VALUE else timestampSeconds.toLong() / SECONDS_PER_DAY

    /** Days since the epoch for a local wall clock time. */
    fun localEpochDay(millis: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = millis
        return daysFromCivil(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** True when both timestamps fall on the same day. */
    fun isSameDay(firstSeconds: Int, secondSeconds: Int): Boolean =
        epochDay(firstSeconds) == epochDay(secondSeconds)

    /** Clock time in the format of the current locale. */
    fun clock(timestampSeconds: Int, locale: Locale = Locale.getDefault()): String {
        val formatter = SimpleDateFormat("HH:mm", locale)
        return formatter.format(Date(timestampSeconds.toLong() * 1000L))
    }

    /** Short date such as "12 May" or "12 May 2025" when the year differs. */
    fun date(
        context: Context,
        timestampSeconds: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val pattern = if (isSameYear(timestampSeconds, nowMillis)) "d MMM" else "d MMM yyyy"
        return SimpleDateFormat(pattern, Locale.getDefault())
            .format(Date(timestampSeconds.toLong() * 1000L))
    }

    /** Time shown in the chat list: clock for today, weekday for the last week, date after. */
    fun chatTimestamp(context: Context, timestampSeconds: Int, nowMillis: Long = System.currentTimeMillis()): String {
        if (timestampSeconds <= 0) return ""
        val today = localEpochDay(nowMillis)
        val day = epochDay(timestampSeconds)
        return when {
            day == today -> clock(timestampSeconds)
            day == today - 1 -> context.getString(R.string.time_yesterday)
            today - day in 2..6 -> weekday(timestampSeconds)
            else -> date(context, timestampSeconds, nowMillis)
        }
    }

    /** Day separator shown inside a conversation. */
    fun daySeparator(context: Context, timestampSeconds: Int, nowMillis: Long = System.currentTimeMillis()): String {
        val today = localEpochDay(nowMillis)
        val day = epochDay(timestampSeconds)
        return when {
            day == today -> context.getString(R.string.message_today)
            day == today - 1 -> context.getString(R.string.message_yesterday)
            else -> date(context, timestampSeconds, nowMillis)
        }
    }

    /** Detail line for a message bubble. */
    fun messageTime(timestampSeconds: Int, locale: Locale = Locale.getDefault()): String = clock(timestampSeconds, locale)

    /** Human readable presence text for profiles and conversation headers. */
    fun status(context: Context, status: UserStatusUi, nowMillis: Long = System.currentTimeMillis()): String? =
        when (status) {
            UserStatusUi.Online -> context.getString(R.string.profile_status_online)
            is UserStatusUi.Offline -> if (status.wasOnline <= 0) {
                context.getString(R.string.profile_status_long_ago)
            } else {
                UiMessage.Res(
                    R.string.profile_status_last_seen,
                    listOf(relativeMoment(context, status.wasOnline, nowMillis))
                ).resolve(context)
            }
            UserStatusUi.Recently -> context.getString(R.string.profile_status_recently)
            UserStatusUi.LastWeek -> context.getString(R.string.profile_status_last_week)
            UserStatusUi.LastMonth -> context.getString(R.string.profile_status_last_month)
            UserStatusUi.Hidden -> context.getString(R.string.profile_status_hidden)
            UserStatusUi.Empty -> null
        }

    private fun relativeMoment(context: Context, timestampSeconds: Int, nowMillis: Long): String {
        val today = localEpochDay(nowMillis)
        val day = epochDay(timestampSeconds)
        return when {
            day == today -> context.getString(R.string.time_today_at, clock(timestampSeconds))
            day == today - 1 -> context.getString(R.string.time_yesterday_at, clock(timestampSeconds))
            else -> context.getString(R.string.time_on_date, date(context, timestampSeconds, nowMillis))
        }
    }

    private fun weekday(timestampSeconds: Int): String =
        SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestampSeconds.toLong() * 1000L))

    private fun isSameYear(timestampSeconds: Int, nowMillis: Long): Boolean {
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val other = Calendar.getInstance().apply { timeInMillis = timestampSeconds.toLong() * 1000L }
        return calendar.get(Calendar.YEAR) == other.get(Calendar.YEAR)
    }

    /** Howard Hinnant's days_from_civil algorithm, used for calendar independent math. */
    internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }
}
