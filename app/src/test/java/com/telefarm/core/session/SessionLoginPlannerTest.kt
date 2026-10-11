package com.telefarm.core.session

import com.telefarm.data.model.AuthState
import com.telefarm.data.model.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a pasted session may be used, and which application id it is used with.
 *
 * TDLib hands out a login token only while it waits for a phone number; at any other moment the
 * request would be dropped and the user would watch a spinner that never stops.
 */
class SessionLoginPlannerTest {

    @Test
    fun `a session can be used while the client waits for a phone number`() {
        assertEquals(
            SessionLoginReadiness.READY,
            SessionLoginPlanner.readiness(AuthState.WaitPhoneNumber)
        )
    }

    @Test
    fun `a session has to wait while the client is starting or closing`() {
        assertEquals(SessionLoginReadiness.CLIENT_BUSY, SessionLoginPlanner.readiness(AuthState.Initializing))
        assertEquals(SessionLoginReadiness.CLIENT_BUSY, SessionLoginPlanner.readiness(AuthState.LoggingOut))
        assertEquals(SessionLoginReadiness.CLIENT_BUSY, SessionLoginPlanner.readiness(AuthState.Closed))
    }

    @Test
    fun `a session is not asked for while a login already runs`() {
        assertEquals(
            SessionLoginReadiness.NOT_WAITING_FOR_LOGIN,
            SessionLoginPlanner.readiness(
                AuthState.WaitCode(codeLength = 5, codeType = AuthState.CodeType.SMS)
            )
        )
        assertEquals(
            SessionLoginReadiness.NOT_WAITING_FOR_LOGIN,
            SessionLoginPlanner.readiness(AuthState.WaitPassword(passwordHint = null, hasRecoveryEmail = false))
        )
        assertEquals(
            SessionLoginReadiness.NOT_WAITING_FOR_LOGIN,
            SessionLoginPlanner.readiness(AuthState.Ready)
        )
        assertEquals(
            SessionLoginReadiness.NOT_WAITING_FOR_LOGIN,
            SessionLoginPlanner.readiness(AuthState.Failed(UiMessage.Res(0)))
        )
        assertEquals(
            SessionLoginReadiness.NOT_WAITING_FOR_LOGIN,
            SessionLoginPlanner.readiness(AuthState.WaitOtherDeviceConfirmation("tg://login?token=abc"))
        )
    }

    @Test
    fun `the application id of the session is preferred`() {
        val session = importedSession(applicationId = 2040)
        assertEquals(2040, SessionLoginPlanner.apiIdFor(session, buildApiId = 6))
    }

    @Test
    fun `a session without an application id uses the id of this build`() {
        val session = importedSession(applicationId = null)
        assertEquals(6, SessionLoginPlanner.apiIdFor(session, buildApiId = 6))
    }

    @Test
    fun `a stored id of zero is treated as missing`() {
        val session = importedSession(applicationId = 0)
        assertEquals(6, SessionLoginPlanner.apiIdFor(session, buildApiId = 6))
    }

    @Test
    fun `the description carries the fingerprint but no key material`() {
        val session = importedSession(applicationId = null)
        val description = SessionLoginPlanner.describeFor(session)
        assertTrue(description.contains(session.keyFingerprint()))
        assertTrue(!description.contains(String(session.authorizationKey.copyOfRange(0, 8), Charsets.ISO_8859_1)))
    }

    private fun importedSession(applicationId: Int?) = ImportedSession(
        dcId = 2,
        authorizationKey = ByteArray(256) { ((it * 3 + 1) % 256).toByte() },
        address = "149.154.167.51",
        port = 443,
        applicationId = applicationId,
        testMode = false,
        format = SessionFormat.TELETHON
    )
}
