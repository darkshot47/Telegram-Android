package com.telefarm.core.td

import android.util.Log

/**
 * Minimal logger.
 *
 * Only class names, resource free descriptions and error types are written; message contents,
 * phone numbers, codes, passwords and session data never reach the log.
 */
object TelefarmLog {

    fun d(tag: String, message: String) {
        if (isDebug) Log.d(tag, message)
    }

    fun w(tag: String, message: String) {
        if (isDebug) Log.w(tag, message)
    }

    /**
     * Errors are always logged as a class name; the throwable itself is never printed so that
     * a stack trace cannot expose request parameters.
     */
    fun e(tag: String, message: String, error: Throwable? = null) {
        val suffix = error?.let { " (${it.javaClass.simpleName})" }.orEmpty()
        Log.e(tag, message + suffix)
    }

    /** Debug builds log more, release builds still log errors only. */
    val isDebug: Boolean
        get() = try {
            com.telefarm.BuildConfig.DEBUG
        } catch (error: Throwable) {
            false
        }
}
