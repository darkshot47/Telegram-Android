package com.telefarm.data.model

import androidx.appcompat.app.AppCompatDelegate

/** Application theme selection. */
enum class ThemeMode(val nightMode: Int) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    DARK(AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        fun fromNightMode(nightMode: Int): ThemeMode =
            entries.firstOrNull { it.nightMode == nightMode } ?: SYSTEM
    }
}

/** Storage usage reported by TDLib for this device. */
data class StorageUsage(
    val databaseBytes: Long,
    val filesBytes: Long,
    val fileCount: Int
) {
    val totalBytes: Long get() = databaseBytes + filesBytes
}

/** Account data of the signed in user, shown in Settings. */
data class AccountUi(
    val userId: Long,
    val displayName: String,
    val firstName: String,
    val lastName: String,
    val username: String?,
    val phoneNumber: String?,
    val bio: String?,
    val photo: FileRef?
)
