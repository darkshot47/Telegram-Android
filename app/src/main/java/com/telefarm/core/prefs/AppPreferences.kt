package com.telefarm.core.prefs

import com.telefarm.core.notifications.NotificationPreview
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.telefarm.core.notifications.QuietHours

/**
 * Interface settings.
 *
 * Nothing sensitive is stored here: no session data, no phone number, no credentials. The
 * file is a normal preference file and can be read by the user on a rooted device without
 * exposing the account.
 */
class AppPreferences(context: Context) {

    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Current night mode, persisted as the `androidx` constant. */
    var themeMode: Int
        get() = preferences.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = preferences.edit().putInt(KEY_THEME, value).apply()

    /** Hides phone numbers in the interface, a device local privacy choice. */
    var hidePhoneNumbers: Boolean
        get() = preferences.getBoolean(KEY_HIDE_PHONE, false)
        set(value) = preferences.edit().putBoolean(KEY_HIDE_PHONE, value).apply()

    /** Hides online and last seen information in the interface. */
    var hideOnlineStatus: Boolean
        get() = preferences.getBoolean(KEY_HIDE_ONLINE, false)
        set(value) = preferences.edit().putBoolean(KEY_HIDE_ONLINE, value).apply()

    /** Whether in-app notifications are shown for incoming messages. */
    var notificationsEnabled: Boolean
        get() = preferences.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = preferences.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    /** The hours of the day when an incoming message stays silent. */
    var quietHours: QuietHours
        get() = QuietHours.decode(preferences.getString(KEY_QUIET_HOURS, null))
        set(value) = preferences.edit().putString(KEY_QUIET_HOURS, QuietHours.encode(value)).apply()

    /**
     * How much a notification says about the message behind it.
     *
     * A name is stored rather than a number, so a setting written by an older build that did not
     * have this one reads as the default instead of as something nobody chose.
     */
    var notificationPreview: NotificationPreview
        get() = preferences.getString(KEY_NOTIFICATION_PREVIEW, null)?.let { stored ->
            NotificationPreview.entries.firstOrNull { it.name == stored }
        } ?: NotificationPreview.FULL
        set(value) = preferences.edit().putString(KEY_NOTIFICATION_PREVIEW, value.name).apply()

    /** Applies the stored theme; called once when the application starts. */
    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(themeMode)
    }

    private companion object {
        const val FILE_NAME = "telefarm.settings"
        const val KEY_THEME = "theme_mode"
        const val KEY_HIDE_PHONE = "hide_phone_numbers"
        const val KEY_HIDE_ONLINE = "hide_online_status"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_QUIET_HOURS = "quiet_hours"
        const val KEY_NOTIFICATION_PREVIEW = "notification_preview"
    }
}
