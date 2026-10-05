package com.telefarm.ui.media

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.data.model.FileRef
import com.telefarm.databinding.ActivityPhotoViewerBinding
import kotlinx.coroutines.launch

/**
 * Full screen viewer for one picture.
 *
 * The picture is fetched through TDLib; a progress indicator is shown while it downloads and
 * an explicit message when it cannot be displayed. No placeholder image is shown instead.
 */
class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoViewerBinding

    private val fileId: Int by lazy { intent.getIntExtra(EXTRA_FILE_ID, FileRef.NO_FILE) }
    private val fileSize: Long by lazy { intent.getLongExtra(EXTRA_FILE_SIZE, 0L) }
    private val localPath: String? by lazy { intent.getStringExtra(EXTRA_LOCAL_PATH) }
    private val isDownloaded: Boolean by lazy { intent.getBooleanExtra(EXTRA_DOWNLOADED, false) }
    private val canBeDownloaded: Boolean by lazy { intent.getBooleanExtra(EXTRA_CAN_DOWNLOAD, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewerClose.setOnClickListener { finish() }
        binding.viewerImage.setOnClickListener { finish() }

        val reference = FileRef(
            id = fileId,
            size = fileSize,
            localPath = localPath,
            isDownloaded = isDownloaded,
            canBeDownloaded = canBeDownloaded
        )
        load(reference)
    }

    private fun load(reference: FileRef) {
        if (reference.id == FileRef.NO_FILE) {
            showError()
            return
        }
        binding.viewerProgress.isVisible = true
        binding.viewerError.isVisible = false
        lifecycleScope.launch {
            val metrics = resources.displayMetrics
            val bitmap = runCatching {
                appGraph.thumbnails.load(reference, metrics.widthPixels, metrics.heightPixels)
            }.getOrNull()
            binding.viewerProgress.isVisible = false
            if (bitmap == null) {
                showError()
            } else {
                binding.viewerImage.setImageBitmap(bitmap)
            }
        }
    }

    private fun showError() {
        binding.viewerProgress.isVisible = false
        binding.viewerError.isVisible = true
        binding.viewerError.setText(R.string.photo_viewer_error)
    }

    companion object {

        private const val EXTRA_FILE_ID = "com.telefarm.extra.FILE_ID"
        private const val EXTRA_FILE_SIZE = "com.telefarm.extra.FILE_SIZE"
        private const val EXTRA_LOCAL_PATH = "com.telefarm.extra.LOCAL_PATH"
        private const val EXTRA_DOWNLOADED = "com.telefarm.extra.DOWNLOADED"
        private const val EXTRA_CAN_DOWNLOAD = "com.telefarm.extra.CAN_DOWNLOAD"

        /** Opens a picture that TDLib knows about. */
        fun intent(context: Context, file: FileRef): Intent = Intent(context, PhotoViewerActivity::class.java)
            .putExtra(EXTRA_FILE_ID, file.id)
            .putExtra(EXTRA_FILE_SIZE, file.size)
            .putExtra(EXTRA_LOCAL_PATH, file.localPath)
            .putExtra(EXTRA_DOWNLOADED, file.isDownloaded)
            .putExtra(EXTRA_CAN_DOWNLOAD, file.canBeDownloaded)
    }
}
