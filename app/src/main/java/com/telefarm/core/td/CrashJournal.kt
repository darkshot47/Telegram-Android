package com.telefarm.core.td

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes down what killed the application.
 *
 * A crash that leaves nothing behind is a crash that cannot be fixed: the screen closes, the
 * person has no idea why, and whoever would repair it has only a description of the moment it
 * happened. So the trace goes into a file in the application's own storage, where it survives the
 * process and can be read from the about screen afterwards.
 *
 * Only the last [KEPT] crashes are kept. They hold no message content and no credentials - a
 * stack trace is a list of the application's own function names - and nothing is ever sent
 * anywhere.
 */
object CrashJournal {

    private const val DIRECTORY = "crashes"
    private const val KEPT = 5
    private const val TAG = "CrashJournal"

    /** Installs the handler, keeping whatever the platform would otherwise have done. */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(context, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(context: Context, thread: Thread, error: Throwable) {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        File(directory, "crash-$stamp.txt").writeText(
            buildString {
                appendLine("thread: ${thread.name}")
                appendLine("android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine()
                append(trace)
            }
        )
        // Oldest first, so the ones dropped are the least recent.
        val recorded = directory.listFiles()?.sortedBy { it.name }.orEmpty()
        if (recorded.size > KEPT) {
            recorded.take(recorded.size - KEPT).forEach { it.delete() }
        }
        TelefarmLog.e(TAG, "recorded a crash", error)
    }

    /** The crashes recorded on this device, newest first, as file name and text. */
    fun read(context: Context): List<Pair<String, String>> =
        File(context.filesDir, DIRECTORY)
            .listFiles()
            ?.sortedByDescending { it.name }
            ?.mapNotNull { file ->
                runCatching { file.name to file.readText() }.getOrNull()
            }
            .orEmpty()
}
