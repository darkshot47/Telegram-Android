package com.telefarm.data

import com.telefarm.core.security.SessionList
import com.telefarm.core.td.TdLibClient
import com.telefarm.data.model.ActiveSessionUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.TdApi

/** What the session screen is showing. */
sealed interface SessionsState {

    data object Loading : SessionsState

    data class Content(val sessions: List<ActiveSessionUi>) : SessionsState

    /** Telegram could not be asked. */
    data class Failed(val message: String?) : SessionsState
}

/** What signing every other device out produced. */
sealed interface TerminateAllResult {

    /** There was no other device. */
    data object NothingToDo : TerminateAllResult

    /** Every other device was signed out. */
    data class Done(val count: Int) : TerminateAllResult

    /** It stopped part way; [title] is the device it could not sign out. */
    data class StoppedAt(val terminated: Int, val title: String) : TerminateAllResult
}

/**
 * The devices signed in to this account.
 *
 * A session is what a stolen account is used from, so this list is where an account is actually
 * protected: a device that is not recognised can be signed out from here, one at a time or all of
 * them at once.
 *
 * Signing a device out cannot be undone from here - that device has to go through the verification
 * code again - so nothing is terminated except what the screen asked for, and this device is never
 * in the list of things that can be terminated.
 */
class SessionsRepository(private val td: TdLibClient) {

    private val _state = MutableStateFlow<SessionsState>(SessionsState.Loading)

    val state: StateFlow<SessionsState> = _state.asStateFlow()

    private val _isWorking = MutableStateFlow(false)

    /** True while a request is on its way, so the screen can show that it has not been ignored. */
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    /** Asks Telegram for the list. Called every time the screen opens. */
    suspend fun load() {
        _state.value = SessionsState.Loading
        _state.value = try {
            val sessions = td.send(TdApi.GetActiveSessions()).sessions.orEmpty().map { it.toUi() }
            SessionsState.Content(SessionList.sort(sessions))
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            SessionsState.Failed(error.message)
        }
    }

    /** Signs one device out and reads the list again, so the row disappears for real. */
    suspend fun terminate(sessionId: Long): Boolean {
        if (!terminateOne(sessionId)) return false
        load()
        return true
    }

    /**
     * Signs every device but this one out.
     *
     * One request per session, stopping at the first failure: a job that stops and says which
     * device it could not reach is more useful than one that quietly left three devices signed in.
     * The list is read again once at the end rather than after every session.
     */
    suspend fun terminateAllOthers(): TerminateAllResult {
        val sessions = (_state.value as? SessionsState.Content)?.sessions.orEmpty()
        val others = SessionList.otherSessions(sessions)
        if (others.isEmpty()) return TerminateAllResult.NothingToDo

        _isWorking.value = true
        var terminated = 0
        try {
            for (session in others) {
                if (!terminateOne(session.id)) {
                    return TerminateAllResult.StoppedAt(terminated, SessionList.title(session))
                }
                terminated++
            }
            return TerminateAllResult.Done(terminated)
        } finally {
            _isWorking.value = false
            load()
        }
    }

    private suspend fun terminateOne(sessionId: Long): Boolean {
        _isWorking.value = true
        return try {
            td.send(TdApi.TerminateSession().apply { this.sessionId = sessionId })
            true
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            false
        } finally {
            _isWorking.value = false
        }
    }

    private fun TdApi.Session.toUi() = ActiveSessionUi(
        id = id,
        isCurrent = isCurrent,
        isPasswordPending = isPasswordPending,
        applicationName = applicationName.orEmpty(),
        applicationVersion = applicationVersion.orEmpty(),
        isOfficialApplication = isOfficialApplication,
        deviceModel = deviceModel.orEmpty(),
        platform = platform.orEmpty(),
        systemVersion = systemVersion.orEmpty(),
        logInDate = logInDate,
        lastActiveDate = lastActiveDate,
        ipAddress = ipAddress.orEmpty(),
        location = location.orEmpty()
    )
}
