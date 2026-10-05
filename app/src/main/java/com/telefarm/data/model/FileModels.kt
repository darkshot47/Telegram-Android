package com.telefarm.data.model

/**
 * Immutable reference to a file managed by TDLib.
 *
 * The UI only ever sees this reference, never a TDLib object, which keeps media handling in
 * the data layer and makes the models testable.
 */
data class FileRef(
    val id: Int,
    val size: Long,
    val localPath: String?,
    val isDownloaded: Boolean,
    val canBeDownloaded: Boolean
) {
    /** Path of the file when it is already on the device. */
    val availablePath: String?
        get() = localPath?.takeIf { isDownloaded && it.isNotEmpty() }

    companion object {
        const val NO_FILE = 0
    }
}

/** Download progress of a single file. */
sealed interface DownloadState {
    data object Idle : DownloadState

    data class Progress(val downloadedBytes: Long, val totalBytes: Long) : DownloadState {
        val percent: Int
            get() = if (totalBytes <= 0L) 0 else ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
    }

    data class Completed(val path: String) : DownloadState

    data class Failed(val reason: String?) : DownloadState
}
