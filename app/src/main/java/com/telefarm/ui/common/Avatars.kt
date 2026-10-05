package com.telefarm.ui.common

import android.content.res.ColorStateList
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.telefarm.R
import com.telefarm.data.model.FileRef
import com.telefarm.media.ThumbnailLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Renders a peer picture with a deterministic initials fallback.
 *
 * The loader is cancellation aware: when a row is recycled the pending job is cancelled and
 * late results are dropped, so a picture never ends up on the wrong chat.
 */
object Avatars {

    private val placeholderColors = intArrayOf(
        R.color.avatar_1,
        R.color.avatar_2,
        R.color.avatar_3,
        R.color.avatar_4,
        R.color.avatar_5,
        R.color.avatar_6,
        R.color.avatar_7,
        R.color.avatar_8
    )

    /** First letter of a display name, used by the placeholder. */
    fun initials(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "?"
        val first = trimmed.firstOrNull { it.isLetterOrDigit() } ?: trimmed.first()
        return first.uppercase()
    }

    /** Stable placeholder color for a peer identifier. */
    fun colorFor(peerId: Long): Int {
        val index = (((peerId % placeholderColors.size) + placeholderColors.size) % placeholderColors.size).toInt()
        return placeholderColors[index]
    }

    /**
     * Binds a photo (or its placeholder) to a row.
     *
     * @param sizePx edge length of the view in pixels, used to decode a right sized bitmap.
     */
    fun bind(
        image: ImageView,
        initialsView: TextView,
        name: String,
        peerId: Long,
        photo: FileRef?,
        loader: ThumbnailLoader,
        scope: CoroutineScope,
        sizePx: Int
    ) {
        cancel(image)
        image.setImageDrawable(null)
        image.isVisible = false

        val color = ContextCompat.getColor(initialsView.context, colorFor(peerId))
        initialsView.text = initials(name)
        initialsView.backgroundTintList = ColorStateList.valueOf(color)
        initialsView.isVisible = true

        if (photo == null || !photo.canBeDownloaded) return

        val token = Any()
        image.setTag(R.id.tag_avatar_request, token)
        val job = scope.launch {
            loader.loadDrawable(
                reference = photo,
                maxWidth = sizePx,
                maxHeight = sizePx,
                circular = true,
                isCurrent = { image.getTag(R.id.tag_avatar_request) === token }
            ) { drawable ->
                if (drawable != null) {
                    image.setImageDrawable(drawable)
                    image.isVisible = true
                    initialsView.isVisible = false
                }
            }
        }
        image.setTag(R.id.tag_avatar_job, job)
    }

    /** Releases a pending avatar request for a view that is going away. */
    fun cancel(image: ImageView) {
        (image.getTag(R.id.tag_avatar_job) as? Job)?.cancel()
        image.setTag(R.id.tag_avatar_job, null)
        image.setTag(R.id.tag_avatar_request, null)
    }
}
