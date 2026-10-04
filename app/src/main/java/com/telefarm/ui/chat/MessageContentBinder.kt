package com.telefarm.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.telefarm.R
import com.telefarm.data.model.DownloadState
import com.telefarm.data.model.FileRef
import com.telefarm.data.model.MessageContentUi
import com.telefarm.data.model.MessageUi
import com.telefarm.media.FileManager
import com.telefarm.media.ThumbnailLoader
import com.telefarm.media.VoicePlayer
import com.telefarm.ui.common.Sizes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Renders the body of one message bubble.
 *
 * Pictures and video messages show their thumbnail as soon as it is available; the full file
 * is downloaded only when the user asks for it and progress bars always reflect a real TDLib
 * transfer, so nothing is presented as available before it exists on the device.
 */
class MessageContentBinder(
    private val fileManager: FileManager,
    private val thumbnails: ThumbnailLoader,
    private val voicePlayer: VoicePlayer,
    private val scope: CoroutineScope,
    private val onOpenFile: (path: String, mimeType: String?) -> Unit,
    private val onError: (CharSequence) -> Unit
) {

    /**
     * Fills [container] with the view for [message].
     *
     * Every coroutine started here is added to [jobs] so the caller can cancel the work when
     * the row is recycled.
     */
    fun bind(container: FrameLayout, message: MessageUi, jobs: MutableList<Job>) {
        container.removeAllViews()
        when (val content = message.content) {
            is MessageContentUi.Text -> bindText(container, content)
            is MessageContentUi.Photo -> bindPhoto(container, content, jobs)
            is MessageContentUi.Video -> bindVideo(container, content, jobs)
            is MessageContentUi.Document -> bindDocument(container, content, jobs)
            is MessageContentUi.Voice -> bindVoice(container, content, jobs)
            is MessageContentUi.Audio -> bindAudio(container, content, jobs)
            is MessageContentUi.Sticker -> bindSticker(container, content, jobs)
            is MessageContentUi.Other -> bindOther(container, content)
        }
    }

    // region Content

    private fun bindText(container: FrameLayout, content: MessageContentUi.Text) {
        val view = inflate(container, R.layout.content_message_text) as TextView
        view.text = content.text
        view.maxWidth = maxBubbleWidth(container)
    }

    private fun bindPhoto(container: FrameLayout, content: MessageContentUi.Photo, jobs: MutableList<Job>) {
        val root = inflate(container, R.layout.content_message_photo)
        val image = root.findViewById<ImageView>(R.id.mediaImage)
        val progress = root.findViewById<ProgressBar>(R.id.mediaProgress)

        viewCaption(root.findViewById(R.id.mediaCaption), content.caption)
        showThumbnail(container, image, content.preview ?: content.full, jobs)

        val target = content.full ?: return
        image.setOnClickListener { openAfterDownload(target, null, progress) }
    }

    private fun bindVideo(container: FrameLayout, content: MessageContentUi.Video, jobs: MutableList<Job>) {
        val root = inflate(container, R.layout.content_message_video)
        val image = root.findViewById<ImageView>(R.id.mediaImage)
        val progress = root.findViewById<ProgressBar>(R.id.mediaProgress)

        viewCaption(root.findViewById(R.id.mediaCaption), content.caption)
        showThumbnail(container, image, content.thumbnail, jobs)

        val target = content.file ?: return
        val open: () -> Unit = { openAfterDownload(target, VIDEO_MIME_TYPE, progress) }
        root.setOnClickListener { open() }
        image.setOnClickListener { open() }
    }

    private fun bindDocument(container: FrameLayout, content: MessageContentUi.Document, jobs: MutableList<Job>) {
        val root = inflate(container, R.layout.content_message_document)
        val name = root.findViewById<TextView>(R.id.documentName)
        val info = root.findViewById<TextView>(R.id.documentInfo)
        val action = root.findViewById<ImageView>(R.id.documentAction)
        val progress = root.findViewById<ProgressBar>(R.id.mediaProgress)

        name.text = content.fileName
        info.text = Sizes.bytes(content.size)
        viewCaption(root.findViewById(R.id.mediaCaption), content.caption)

        val target = content.file ?: return
        val open: () -> Unit = { openAfterDownload(target, content.mimeType, progress) }
        action.setOnClickListener { open() }
        root.setOnClickListener { open() }
        jobs += scope.launch {
            fileManager.download(target, FileManager.PRIORITY_DOCUMENT).collect { state ->
                when (state) {
                    is DownloadState.Completed -> {
                        progress.isVisible = false
                        action.setImageResource(R.drawable.ic_file)
                    }
                    is DownloadState.Progress -> {
                        progress.isVisible = true
                        progress.progress = state.percent
                    }
                    is DownloadState.Failed -> progress.isVisible = false
                    DownloadState.Idle -> Unit
                }
            }
        }
    }

    private fun bindVoice(container: FrameLayout, content: MessageContentUi.Voice, jobs: MutableList<Job>) {
        val context = container.context
        val root = inflate(container, R.layout.content_message_voice)
        val button = root.findViewById<ImageView>(R.id.voiceButton)
        val progress = root.findViewById<ProgressBar>(R.id.voiceProgress)
        val duration = root.findViewById<TextView>(R.id.voiceDuration)

        duration.text = Sizes.duration(content.duration)
        val file = content.file

        jobs += scope.launch {
            voicePlayer.state.collect { state ->
                val active = file != null && state.fileId == file.id
                button.setImageResource(if (active && state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                progress.progress = if (active && state.durationMs > 0) {
                    (state.positionMs * 100 / state.durationMs).coerceIn(0, 100)
                } else {
                    0
                }
            }
        }

        val play: () -> Unit = {
            if (file == null) {
                Unit
            } else {
                val local = localPathOf(file)
                if (local != null) {
                    voicePlayer.toggle(file.id, local)
                } else {
                    jobs += scope.launch {
                        fileManager.download(file, FileManager.PRIORITY_AUDIO).collect { state ->
                            when (state) {
                                is DownloadState.Completed -> voicePlayer.toggle(file.id, state.path)
                                is DownloadState.Progress -> progress.progress = state.percent
                                is DownloadState.Failed ->
                                    onError(context.getString(R.string.message_download_failed))
                                DownloadState.Idle -> Unit
                            }
                        }
                    }
                }
            }
        }
        button.setOnClickListener { play() }
        root.setOnClickListener { play() }
    }

    private fun bindAudio(container: FrameLayout, content: MessageContentUi.Audio, jobs: MutableList<Job>) {
        val context = container.context
        val root = inflate(container, R.layout.content_message_audio)
        val title = root.findViewById<TextView>(R.id.audioTitle)
        val info = root.findViewById<TextView>(R.id.audioInfo)
        val action = root.findViewById<ImageView>(R.id.audioAction)

        title.text = content.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.message_audio)
        info.text = listOfNotNull(
            content.performer?.takeIf { it.isNotBlank() },
            Sizes.duration(content.duration)
        ).joinToString(" - ")

        val file = content.file ?: return
        jobs += scope.launch {
            voicePlayer.state.collect { state ->
                val active = state.fileId == file.id
                action.setImageResource(if (active && state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            }
        }

        val play: () -> Unit = {
            val local = localPathOf(file)
            if (local != null) {
                voicePlayer.toggle(file.id, local)
            } else {
                jobs += scope.launch {
                    fileManager.download(file, FileManager.PRIORITY_AUDIO).collect { state ->
                        if (state is DownloadState.Completed) voicePlayer.toggle(file.id, state.path)
                    }
                }
            }
        }
        action.setOnClickListener { play() }
        root.setOnClickListener { play() }
    }

    private fun bindSticker(container: FrameLayout, content: MessageContentUi.Sticker, jobs: MutableList<Job>) {
        val root = inflate(container, R.layout.content_message_sticker)
        val image = root.findViewById<ImageView>(R.id.stickerImage)
        val placeholder = root.findViewById<TextView>(R.id.stickerPlaceholder)

        if (content.isAnimated || content.file == null) {
            // Animated stickers need a player, so a label is shown instead of a frozen frame.
            placeholder.isVisible = true
            image.isVisible = false
            return
        }
        val size = container.resources.getDimensionPixelSize(R.dimen.message_sticker_size)
        jobs += scope.launch {
            val bitmap = runCatching { thumbnails.load(content.file, size, size) }.getOrNull()
            if (bitmap != null) {
                image.setImageBitmap(bitmap)
                image.isVisible = true
                placeholder.isVisible = false
            } else {
                placeholder.isVisible = true
                image.isVisible = false
            }
        }
    }

    private fun bindOther(container: FrameLayout, content: MessageContentUi.Other) {
        val view = inflate(container, R.layout.content_message_other) as TextView
        view.text = content.label
        view.maxWidth = maxBubbleWidth(container)
    }

    // endregion

    // region Helpers

    private fun inflate(container: FrameLayout, layoutRes: Int): View {
        val view = LayoutInflater.from(container.context).inflate(layoutRes, container, false)
        container.addView(view)
        return view
    }

    private fun showThumbnail(container: FrameLayout, image: ImageView, reference: FileRef?, jobs: MutableList<Job>) {
        image.setImageDrawable(null)
        if (reference == null) return
        val width = maxBubbleWidth(container)
        val height = container.resources.getDimensionPixelSize(R.dimen.message_photo_max_height)
        jobs += scope.launch {
            val bitmap = runCatching { thumbnails.load(reference, width, height) }.getOrNull()
            if (bitmap != null) image.setImageBitmap(bitmap)
        }
    }

    /** Downloads [reference] and hands the finished file to the system. */
    private fun openAfterDownload(reference: FileRef, mimeType: String?, progress: ProgressBar) {
        localPathOf(reference)?.let { path ->
            onOpenFile(path, mimeType)
            return
        }
        scope.launch {
            fileManager.download(reference, FileManager.PRIORITY_DOCUMENT).collect { state ->
                when (state) {
                    is DownloadState.Completed -> {
                        progress.isVisible = false
                        onOpenFile(state.path, mimeType)
                    }
                    is DownloadState.Progress -> {
                        progress.isVisible = true
                        progress.progress = state.percent
                    }
                    is DownloadState.Failed -> {
                        progress.isVisible = false
                        onError(progress.context.getString(R.string.message_download_failed))
                    }
                    DownloadState.Idle -> Unit
                }
            }
        }
    }

    private fun viewCaption(caption: TextView, text: String?) {
        caption.isVisible = !text.isNullOrBlank()
        caption.text = text.orEmpty()
    }

    private fun maxBubbleWidth(container: FrameLayout): Int {
        val fraction = container.resources.getDimension(R.dimen.message_bubble_max_width_percent) / 100f
        return (container.resources.displayMetrics.widthPixels * fraction).toInt()
    }

}
