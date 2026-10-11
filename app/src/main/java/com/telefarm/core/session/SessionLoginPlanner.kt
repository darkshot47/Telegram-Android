package com.telefarm.core.session

import com.telefarm.data.model.AuthState

/** Whether a pasted session can start a login right now. */
enum class SessionLoginReadiness {

    /** The client waits for a sign in, so a login token can be requested. */
    READY,

    /** TDLib is still starting or is closing; the paste has to wait. */
    CLIENT_BUSY,

    /** The account is already signed in, or Telegram reported a failure that must be retried. */
    NOT_WAITING_FOR_LOGIN
}

/**
 * The decisions of the session login that do not need a device.
 *
 * Keeping them here means the sign in view model only runs them and shows the outcome, and the
 * behaviour itself can be exercised without Android, TDLib or a network.
 */
object SessionLoginPlanner {

    /**
     * Tells whether a session can be used against the current authorization state.
     *
     * TDLib hands out a login token only while it waits for a phone number, so pasting a session
     * at any other moment would produce a request that disappears.
     */
    fun readiness(state: AuthState): SessionLoginReadiness = when (state) {
        AuthState.WaitPhoneNumber -> SessionLoginReadiness.READY
        AuthState.Initializing, AuthState.LoggingOut, AuthState.Closed -> SessionLoginReadiness.CLIENT_BUSY
        else -> SessionLoginReadiness.NOT_WAITING_FOR_LOGIN
    }

    /**
     * Application id the session should be used with.
     *
     * A session string written by Hydrogram or by the older Pyrogram layout carries the id of the
     * application that created it. Answering Telegram with that id keeps the imported session
     * looking exactly like the client it came from, which is what the data center expects when it
     * is asked to accept a login token. Formats without an id fall back to the id of this build.
     */
    fun apiIdFor(session: ImportedSession, buildApiId: Int): Int =
        session.applicationId?.takeIf { it > 0 } ?: buildApiId

    /**
     * Line the sign in screen shows under the input.
     *
     * It names the data center, the client that wrote the session and a short digest of the key,
     * so two sessions of the same account can be told apart without any key material appearing.
     */
    fun describeFor(session: ImportedSession): String = session.describeWithFingerprint()
}
