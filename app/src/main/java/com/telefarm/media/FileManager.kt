package com.telefarm.media

import com.telefarm.core.td.TdLibClient
import com.telefarm.core.td.TelefarmLog
import com.telefarm.data.map.TdMappers
import com.telefarm.data.model.DownloadState
import com.telefarm.data.model.FileRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Downloads files through TDLib.
 *
 * Everything stays inside the private application directory and honours Telegram's own cache
 * management; nothing is copied to shared storage unless the user opens a file explicitly.
 */
class FileManager(
    private val td: TdLibClient,
    private val scope: CoroutineScope
) {

    /**
     * Starts (or joins) a download and reports its progress.
     *
     * The flow completes when the transfer finishes or fails. Cancelling the collection stops
     * observing; the download itself continues in TDLib unless [cancelDownload] is called.
     */
    fun download(
        reference: FileRef?,
        priority: Int = PRIORITY_DEFAULT
    ): Flow<DownloadState> = channelFlow {
        if (reference == null || reference.id == FileRef.NO_FILE) {
            send(DownloadState.Failed(null))
            close()
            return@channelFlow
        }
        reference.availablePath?.let { path ->
            send(DownloadState.Completed(path))
            close()
            return@channelFlow
        }

        val observer = launch {
            td.updates
                .filterIsInstance<TdApi.UpdateFile>()
                .filter { update -> update.file.id == reference.id }
                .collect { update ->
                    val state = stateOf(update.file)
                    send(state)
                    if (state is DownloadState.Completed || state is DownloadState.Failed) {
                        close()
                    }
                }
        }

        try {
            val file = td.send(
                TdApi.DownloadFile().apply {
                    fileId = reference.id
                    this.priority = priority
                    offset = 0
                    limit = 0
                    synchronous = false
                }
            )
            val state = stateOf(file)
            send(state)
            if (state is DownloadState.Completed) {
                close()
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            TelefarmLog.w(TAG, "Download could not be started")
            send(DownloadState.Failed(null))
            close()
        }

        observer.join()
    }

    /** Current state of a file without starting a download. */
    suspend fun state(reference: FileRef?): DownloadState {
        if (reference == null || reference.id == FileRef.NO_FILE) return DownloadState.Failed(null)
        reference.availablePath?.let { path -> return DownloadState.Completed(path) }
        return try {
            stateOf(td.send(TdApi.GetFile().apply { fileId = reference.id }))
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            DownloadState.Failed(null)
        }
    }

    /** Refreshes a stale reference with the state TDLib currently reports. */
    suspend fun refresh(reference: FileRef?): FileRef? {
        if (reference == null || reference.id == FileRef.NO_FILE) return null
        return try {
            TdMappers.fileRef(td.send(TdApi.GetFile().apply { fileId = reference.id })) ?: reference
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            reference
        }
    }

    /** Stops an active download; TDLib keeps the partial file for a later resume. */
    fun cancelDownload(fileId: Int) {
        if (fileId == FileRef.NO_FILE) return
        scope.launch {
            runCatching {
                td.send(
                    TdApi.CancelDownloadFile().apply {
                        this.fileId = fileId
                        onlyIfPending = false
                    }
                )
            }
        }
    }

    /** Deletes a downloaded file from this device; Telegram keeps the remote copy. */
    fun delete(fileId: Int) {
        if (fileId == FileRef.NO_FILE) return
        scope.launch {
            runCatching { td.send(TdApi.DeleteFile().apply { this.fileId = fileId }) }
        }
    }

    private fun stateOf(file: TdApi.File): DownloadState {
        val local = file.local
        return when {
            local?.isDownloadingCompleted == true -> DownloadState.Completed(local.path.orEmpty())
            local?.isDownloadingActive == true -> DownloadState.Progress(
                downloadedBytes = local.downloadedSize,
                totalBytes = if (file.size > 0) file.size else file.expectedSize
            )
            else -> DownloadState.Progress(
                downloadedBytes = local?.downloadedSize ?: 0L,
                totalBytes = if (file.size > 0) file.size else file.expectedSize
            )
        }
    }

    companion object {
        private const val TAG = "FileManager"

        /** Priorities understood by TDLib; thumbnails must win over full files. */
        const val PRIORITY_THUMBNAIL = 32
        const val PRIORITY_DEFAULT = 16
        const val PRIORITY_PRELOAD = 1
    }
}
