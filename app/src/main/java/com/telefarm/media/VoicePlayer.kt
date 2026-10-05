package com.telefarm.media

import android.media.MediaPlayer
import com.telefarm.core.td.TelefarmLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Playback state of a voice message or audio file. */
data class PlaybackState(
    val fileId: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0
)

/**
 * Plays a single local audio file at a time using the platform media player.
 *
 * Voice messages are downloaded through TDLib first; this class only ever plays files that
 * already exist in private application storage.
 */
class VoicePlayer(private val scope: CoroutineScope) {

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Starts playback, or pauses when the same file is already playing. */
    fun toggle(fileId: Int, path: String) {
        val current = _state.value
        if (current.fileId == fileId && mediaPlayer != null) {
            if (current.isPlaying) pause() else resume()
            return
        }
        stop()
        start(fileId, path)
    }

    private fun start(fileId: Int, path: String) {
        try {
            val player = MediaPlayer()
            player.setDataSource(path)
            player.prepare()
            player.setOnCompletionListener { stop() }
            player.setOnErrorListener { _, _, _ ->
                TelefarmLog.w(TAG, "Audio playback failed")
                stop()
                true
            }
            player.start()
            mediaPlayer = player
            _state.value = PlaybackState(
                fileId = fileId,
                isPlaying = true,
                positionMs = 0,
                durationMs = player.duration.coerceAtLeast(0)
            )
            startProgressUpdates()
        } catch (error: Throwable) {
            TelefarmLog.w(TAG, "Audio file could not be played")
            stop()
        }
    }

    private fun pause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                _state.value = _state.value.copy(isPlaying = false)
            }
        }
    }

    private fun resume() {
        mediaPlayer?.let { player ->
            player.start()
            _state.value = _state.value.copy(isPlaying = true)
            startProgressUpdates()
        }
    }

    fun stop() {
        progressJob?.cancel()
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        mediaPlayer = null
        _state.value = PlaybackState()
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                val player = mediaPlayer ?: break
                val position = runCatching { player.currentPosition }.getOrDefault(0)
                _state.value = _state.value.copy(positionMs = position)
                if (!_state.value.isPlaying) break
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    /** Releases the player; call this when the screen is destroyed. */
    fun release() = stop()

    private companion object {
        const val TAG = "VoicePlayer"
        const val PROGRESS_INTERVAL_MS = 200L
    }
}
