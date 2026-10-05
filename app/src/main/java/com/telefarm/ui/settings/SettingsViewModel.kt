package com.telefarm.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telefarm.R
import com.telefarm.core.AppGraph
import com.telefarm.data.AppSettings
import com.telefarm.data.model.StorageUsage
import com.telefarm.data.model.AccountUi
import com.telefarm.data.model.ThemeMode
import com.telefarm.data.model.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State of the settings screen. */
data class SettingsScreenState(
    val settings: AppSettings = AppSettings(),
    val account: AccountUi? = null,
    val storage: StorageUsage? = null,
    val isCalculatingStorage: Boolean = true,
    val isClearingCache: Boolean = false
)

/**
 * Settings logic.
 *
 * Every entry has real behaviour: the theme switches the whole application, the privacy
 * switches change what this device displays, notifications control the in-app notification
 * centre, storage is measured by TDLib and logout ends the session.
 */
class SettingsViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(SettingsScreenState())
    val state: StateFlow<SettingsScreenState> = _state.asStateFlow()

    private val _events = MutableStateFlow<UiMessage?>(null)
    val events: StateFlow<UiMessage?> = _events.asStateFlow()

    init {
        viewModelScope.launch {
            graph.settings.settings.collect { settings ->
                _state.value = _state.value.copy(settings = settings)
            }
        }
        loadAccount()
        loadStorage()
    }

    /** Profile of the signed in user, shown at the top of the screen. */
    fun loadAccount() {
        viewModelScope.launch {
            _state.value = _state.value.copy(account = graph.settings.account())
        }
    }

    /** Storage usage reported by TDLib. */
    fun loadStorage() {
        _state.value = _state.value.copy(isCalculatingStorage = true)
        viewModelScope.launch {
            val usage = graph.settings.storageUsage()
            _state.value = _state.value.copy(storage = usage, isCalculatingStorage = false)
        }
    }

    fun setTheme(mode: ThemeMode) {
        graph.settings.setTheme(mode)
    }

    fun setHidePhoneNumbers(hide: Boolean) {
        graph.settings.setHidePhoneNumbers(hide)
    }

    fun setHideOnlineStatus(hide: Boolean) {
        graph.settings.setHideOnlineStatus(hide)
    }

    /**
     * Turns message notifications on or off.
     *
     * Returns false when the system permission is missing, so the screen can ask for it before
     * the switch reports a state that cannot be delivered.
     */
    fun setNotificationsEnabled(enabled: Boolean): Boolean {
        if (enabled && !graph.notifications.hasPermission()) return false
        graph.settings.setNotificationsEnabled(enabled)
        return true
    }

    /** Removes downloaded files; the result is reported through [events]. */
    fun clearCache() {
        if (_state.value.isClearingCache) return
        _state.value = _state.value.copy(isClearingCache = true)
        viewModelScope.launch {
            val cleared = try {
                graph.settings.clearCache()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                false
            }
            _state.value = _state.value.copy(isClearingCache = false)
            _events.value = UiMessage.Res(
                if (cleared) R.string.settings_storage_clear_cache_done else R.string.error_generic
            )
            if (cleared) loadStorage()
        }
    }

    /** Signs out and returns to the sign in screen. */
    fun logOut() {
        viewModelScope.launch {
            graph.notifications.dismissAll()
            graph.messages.releaseAll()
            graph.settings.logOut()
            _events.value = null
        }
    }

    fun clearEvent() {
        _events.value = null
    }
}
