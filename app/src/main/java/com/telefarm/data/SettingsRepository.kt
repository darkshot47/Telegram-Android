package com.telefarm.data

import com.telefarm.core.prefs.AppPreferences
import com.telefarm.core.td.TdLibClient
import com.telefarm.core.td.TelefarmLog
import com.telefarm.data.map.TdMappers
import com.telefarm.data.model.AccountUi
import com.telefarm.data.model.StorageUsage
import com.telefarm.data.model.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.TdApi

/** Everything the settings screen can change, with real behaviour behind it. */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val hidePhoneNumbers: Boolean = false,
    val hideOnlineStatus: Boolean = false,
    val notificationsEnabled: Boolean = true
)

/**
 * Local settings, storage statistics and logout.
 *
 * The privacy switches are device local presentation choices and are labelled as such in the
 * interface; nothing here pretends to change Telegram side privacy.
 */
class SettingsRepository(
    private val td: TdLibClient,
    private val preferences: AppPreferences,
    private val session: TdSessionController,
    private val users: UserRepository
) {

    private val _settings = MutableStateFlow(readSettings())

    /** Current interface settings. */
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Applies the stored theme; called while the application starts. */
    fun applyTheme() {
        preferences.applyTheme()
    }

    fun setTheme(mode: ThemeMode) {
        preferences.themeMode = mode.nightMode
        preferences.applyTheme()
        _settings.value = readSettings()
    }

    fun setHidePhoneNumbers(hide: Boolean) {
        preferences.hidePhoneNumbers = hide
        _settings.value = readSettings()
    }

    fun setHideOnlineStatus(hide: Boolean) {
        preferences.hideOnlineStatus = hide
        _settings.value = readSettings()
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        preferences.notificationsEnabled = enabled
        _settings.value = readSettings()
    }

    private fun readSettings() = AppSettings(
        theme = ThemeMode.fromNightMode(preferences.themeMode),
        hidePhoneNumbers = preferences.hidePhoneNumbers,
        hideOnlineStatus = preferences.hideOnlineStatus,
        notificationsEnabled = preferences.notificationsEnabled
    )

    /** Account data of the signed in user. */
    suspend fun account(): AccountUi? {
        val userId = session.selfUserId.value
        if (userId == 0L) return null
        val user = users.user(userId) ?: return null
        val mapped = TdMappers.user(user)
        return AccountUi(
            userId = mapped.id,
            displayName = mapped.displayName,
            firstName = mapped.firstName,
            lastName = mapped.lastName,
            username = mapped.username,
            phoneNumber = mapped.phoneNumber,
            bio = users.fullInfo(userId)?.bio?.text?.takeIf { it.isNotBlank() },
            photo = mapped.photo
        )
    }

    /** Saves the name of the signed in account. */
    suspend fun updateName(firstName: String, lastName: String): Boolean = try {
        td.send(
            TdApi.SetName().apply {
                this.firstName = firstName.trim()
                this.lastName = lastName.trim()
            }
        )
        true
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        TelefarmLog.w(TAG, "Name could not be saved")
        false
    }

    /** Saves the biography of the signed in account. */
    suspend fun updateBio(bio: String): Boolean = try {
        td.send(TdApi.SetBio().apply { this.bio = bio.trim() })
        true
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        TelefarmLog.w(TAG, "Biography could not be saved")
        false
    }

    /** Storage usage of this device as reported by TDLib. */
    suspend fun storageUsage(): StorageUsage? = try {
        val statistics = td.send(TdApi.GetStorageStatisticsFast())
        StorageUsage(
            databaseBytes = statistics.databaseSize + statistics.languagePackDatabaseSize,
            filesBytes = statistics.filesSize,
            fileCount = statistics.fileCount
        )
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        null
    }

    /** Removes downloaded files and cached media; Telegram keeps the remote copies. */
    suspend fun clearCache(): Boolean = try {
        td.send(
            TdApi.OptimizeStorage().apply {
                size = 0
                ttl = 0
                count = MAX_CACHE_FILES
                immunityDelay = 0
                fileTypes = emptyArray()
                chatIds = longArrayOf()
                excludeChatIds = longArrayOf()
                returnDeletedFileStatistics = false
                chatLimit = CACHE_CHAT_LIMIT
            }
        )
        true
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        false
    }

    /** Signs out; the session directory and the stored encryption key are removed. */
    suspend fun logOut() = session.logOut()

    private companion object {
        const val TAG = "SettingsRepository"
        const val MAX_CACHE_FILES = 100_000
        const val CACHE_CHAT_LIMIT = 100
    }
}
