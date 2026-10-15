package com.telefarm.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telefarm.core.td.TelefarmLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Launches work that must not be allowed to take the application with it.
 *
 * An exception that escapes `viewModelScope.launch` is uncaught, and an uncaught exception ends
 * the process. That turns "Telegram did not answer" into a screen that simply vanishes, which is
 * the least informative thing a failure can do - the person sees the application close and has no
 * idea whether it was something they did.
 *
 * Almost everything a view model asks of Telegram can fail: the client may not be started, the
 * account may be signed out, the network may be gone, the server may refuse. None of those is a
 * reason to die, so they are reported to [onError] and the screen stays put.
 *
 * Cancellation is rethrown. It is how a scope is torn down when its screen closes, and swallowing
 * it would leave work running past the thing that started it.
 */
fun ViewModel.launchGuarded(
    onError: (Throwable) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit
): Job = viewModelScope.launch {
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        TelefarmLog.e(javaClass.simpleName, "background work failed", error)
        onError(error)
    }
}
