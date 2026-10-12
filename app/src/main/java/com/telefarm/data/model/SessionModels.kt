package com.telefarm.data.model

/**
 * One device that is signed in to this account.
 *
 * Only what Telegram reports about the session is kept, and nothing about the account that is not
 * already visible to anybody holding the device: no auth key, no secret, no token. A session list
 * is a list of places, not of credentials.
 */
data class ActiveSessionUi(
    val id: Long,
    val isCurrent: Boolean,

    /** A second password is being asked of this session. */
    val isPasswordPending: Boolean,

    val applicationName: String,
    val applicationVersion: String,
    val isOfficialApplication: Boolean,

    /** Device name as the client reported it. */
    val deviceModel: String,

    /** Operating system name as the client reported it. */
    val platform: String,

    val systemVersion: String,

    /** Seconds since the epoch. */
    val logInDate: Int,

    /** Seconds since the epoch. */
    val lastActiveDate: Int,

    val ipAddress: String,
    val location: String
)
