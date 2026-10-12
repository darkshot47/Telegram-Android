package com.telefarm.core.security

import com.telefarm.core.list.DateBucket
import com.telefarm.core.time.CivilDate
import com.telefarm.data.model.ActiveSessionUi

/**
 * How the list of signed-in devices is read.
 *
 * The one thing a person looks for in this list is a device they do not recognise, so the order
 * and the wording put the odd one where it will be seen: this device first, then everything else
 * by how recently it was used, because a session that has not been touched in a year is either
 * an old phone or somebody who lost interest.
 */
object SessionList {

    /** This device first, then the rest by most recent activity. */
    fun sort(sessions: List<ActiveSessionUi>): List<ActiveSessionUi> =
        sessions.sortedWith(
            compareByDescending<ActiveSessionUi> { it.isCurrent }
                .thenByDescending { it.lastActiveDate.toLong() }
                .thenBy { it.id }
        )

    /** The application, with its version when it has one. */
    fun title(session: ActiveSessionUi): String {
        val name = session.applicationName.ifBlank { UNKNOWN_APPLICATION }
        val version = session.applicationVersion.trim()
        return if (version.isEmpty()) name else "$name $version"
    }

    /** The device, the system and the client's own version of it, minus the parts that repeat. */
    fun subtitle(session: ActiveSessionUi): String {
        val device = session.deviceModel.ifBlank { session.platform }
        val system = session.systemVersion.trim()
        return when {
            device.isBlank() && system.isBlank() -> UNKNOWN_DEVICE
            system.isBlank() || device.contains(system, ignoreCase = true) -> device
            else -> "$device · $system"
        }
    }

    /** Where the session is, which is the part worth reading when something looks wrong. */
    fun place(session: ActiveSessionUi): String {
        val location = session.location.trim()
        val address = session.ipAddress.trim()
        return when {
            location.isNotEmpty() && address.isNotEmpty() -> "$location · $address"
            location.isNotEmpty() -> location
            address.isNotEmpty() -> address
            else -> ""
        }
    }

    /** How long ago the session was used, in the same buckets the chat list uses. */
    fun activity(session: ActiveSessionUi, todayEpochDay: Long): DateBucket {
        if (session.lastActiveDate <= 0) return DateBucket.EARLIER
        val ageDays = todayEpochDay - CivilDate.epochDayOf(session.lastActiveDate.toLong())
        return when {
            ageDays <= 0 -> DateBucket.TODAY
            ageDays == 1L -> DateBucket.YESTERDAY
            ageDays < DAYS_PER_WEEK -> DateBucket.THIS_WEEK
            ageDays < DAYS_PER_MONTH -> DateBucket.THIS_MONTH
            else -> DateBucket.EARLIER
        }
    }

    /**
     * True when the session can be signed out from here.
     *
     * This device is left out, because signing yourself out of the screen that lists your sessions
     * is a way to lose an account, not a way to protect it. The logout that is meant for this
     * device already has its own door, with its own warning.
     */
    fun canTerminate(session: ActiveSessionUi): Boolean = !session.isCurrent

    /** Sessions other than this device, which is what "sign out everywhere else" means. */
    fun otherSessions(sessions: List<ActiveSessionUi>): List<ActiveSessionUi> =
        sessions.filter { canTerminate(it) }

    /** Ids of every session but this one. */
    fun otherSessionIds(sessions: List<ActiveSessionUi>): List<Long> =
        otherSessions(sessions).map { it.id }

    /** True when the list holds at least one session that is not this device. */
    fun hasOtherSessions(sessions: List<ActiveSessionUi>): Boolean = otherSessions(sessions).isNotEmpty()

    private const val DAYS_PER_WEEK = 7L
    private const val DAYS_PER_MONTH = 30L

    const val UNKNOWN_APPLICATION = "Unknown application"
    const val UNKNOWN_DEVICE = "Unknown device"
}
