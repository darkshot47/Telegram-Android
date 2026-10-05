package com.telefarm.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.telefarm.core.td.TelefarmLog
import java.io.File

/**
 * Hands a downloaded file to the system so the user can open it with any application that
 * handles the type. The file is shared through a content URI, never through a public path.
 */
object MediaOpener {

    fun open(context: Context, path: String, mimeType: String?): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType?.takeIf { it.isNotBlank() } ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (error: ActivityNotFoundException) {
            TelefarmLog.w(TAG, "No application can open the file")
            false
        } catch (error: Throwable) {
            TelefarmLog.w(TAG, "File could not be shared")
            false
        }
    }

    private const val TAG = "MediaOpener"
}
