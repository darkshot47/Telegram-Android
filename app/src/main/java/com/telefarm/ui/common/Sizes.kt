package com.telefarm.ui.common

import java.util.Locale

/**
 * Human readable sizes and durations.
 *
 * Kept in one place so the chat list, the conversation and the settings screen describe the
 * same file in the same way.
 */
object Sizes {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    /** Size such as "1.4 MB"; an empty string for unknown sizes. */
    fun bytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < UNITS.lastIndex) {
            value /= 1024
            index++
        }
        val formatted = if (value >= 100 || index == 0) {
            value.toLong().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }
        return "$formatted ${UNITS[index]}"
    }

    /** Size that also answers for a zero value, used by the storage rows. */
    fun bytesOrZero(bytes: Long): String = bytes(bytes).ifEmpty { "0 B" }

    /** Duration as m:ss or h:mm:ss. */
    fun duration(seconds: Int): String {
        if (seconds <= 0) return "--:--"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, secs)
        }
    }
}
