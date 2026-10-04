package com.telefarm.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.telefarm.R
import com.telefarm.core.td.TelefarmLog
import com.telefarm.data.model.DownloadState
import com.telefarm.data.model.FileRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Loads pictures through TDLib with a memory cache and downsampling.
 *
 * The loader returns plain bitmaps; callers decide how they are shown and are responsible for
 * ignoring results of recycled views using a request token.
 */
class ThumbnailLoader(
    private val context: Context,
    private val fileManager: FileManager,
    private val scope: CoroutineScope
) {

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Loads a bitmap, downloading the file first when needed. Returns null on failure. */
    suspend fun load(reference: FileRef?, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (reference == null || reference.id == FileRef.NO_FILE) return null

        val width = max(1, maxWidth)
        val height = max(1, maxHeight)
        val cacheKey = "${reference.id}:${width}x$height"
        memoryCache.get(cacheKey)?.let { cached -> return cached }

        val path = reference.availablePath ?: when (
            val state = fileManager.download(reference, FileManager.PRIORITY_THUMBNAIL).awaitCompletion()
        ) {
            is DownloadState.Completed -> state.path
            else -> null
        } ?: return null

        val bitmap = decodeSampled(path, width, height) ?: return null
        memoryCache.put(cacheKey, bitmap)
        return bitmap
    }

    /**
     * Loads a bitmap and delivers it on the main thread.
     *
     * @param isCurrent checked before the result is delivered, so results of recycled views
     *        are dropped instead of appearing on the wrong row.
     */
    fun load(
        reference: FileRef?,
        maxWidth: Int,
        maxHeight: Int,
        isCurrent: () -> Boolean,
        onResult: (Bitmap?) -> Unit
    ) {
        if (reference == null) {
            onResult(null)
            return
        }
        scope.launch {
            val bitmap = try {
                load(reference, maxWidth, maxHeight)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                TelefarmLog.w(TAG, "Picture could not be loaded")
                null
            }
            withContext(Dispatchers.Main) {
                if (isCurrent()) onResult(bitmap)
            }
        }
    }

    /** Drops every cached bitmap; called when the system is low on memory. */
    fun clear() {
        memoryCache.evictAll()
    }

    /** Memory budget of the bitmap cache: one eighth of the heap. */
    private fun cacheSizeBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).toInt()

    private suspend fun decodeSampled(path: String, maxWidth: Int, maxHeight: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) return@withContext null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, options)
        }

    private fun sampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        var sample = 1
        var currentWidth = width
        var currentHeight = height
        while (currentWidth / 2 >= maxWidth && currentHeight / 2 >= maxHeight) {
            currentWidth /= 2
            currentHeight /= 2
            sample *= 2
        }
        return sample
    }

    /** Loads a rounded or circular drawable, used for avatars. */
    fun loadDrawable(
        reference: FileRef?,
        maxWidth: Int,
        maxHeight: Int,
        circular: Boolean,
        isCurrent: () -> Boolean,
        onResult: (android.graphics.drawable.Drawable?) -> Unit
    ) {
        load(reference, maxWidth, maxHeight, isCurrent) { bitmap ->
            if (bitmap == null) {
                onResult(null)
                return@load
            }
            val drawable = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
                .create(context.resources, bitmap)
                .apply {
                    this.isCircular = circular
                    setAntiAlias(true)
                    if (!circular) cornerRadius = context.resources.getDimension(R.dimen.message_thumbnail_corner)
                }
            onResult(drawable)
        }
    }

    private companion object {
        const val TAG = "ThumbnailLoader"
    }
}

/** Waits for the terminal state of a download flow. */
private suspend fun kotlinx.coroutines.flow.Flow<DownloadState>.awaitCompletion(): DownloadState =
    kotlinx.coroutines.flow.firstOrNull { state ->
        state is DownloadState.Completed || state is DownloadState.Failed
    } ?: DownloadState.Failed(null)
