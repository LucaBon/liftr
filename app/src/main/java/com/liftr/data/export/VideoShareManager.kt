package com.liftr.data.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.liftr.domain.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android-layer helper for sharing and exporting the annotated workout video
 * (Feature 09). Kept out of the domain layer because it touches FileProvider,
 * MediaStore, and Intents.
 */
@Singleton
class VideoShareManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) {

    /** Launch the system share sheet for [file] via the app FileProvider. */
    fun share(file: File, chooserTitle: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}$PROVIDER_SUFFIX", file
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }.onFailure { logger.e(TAG, "Share failed", it) }
    }

    /**
     * Copy [file] into the public gallery under Movies/Liftr using
     * MediaStore (scoped storage, no permission needed on API 29+).
     *
     * @return true if the video was added to the gallery.
     */
    fun saveToGallery(file: File): Boolean {
        if (!file.exists()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(file)
        } else {
            saveLegacy(file)
        }
    }

    private fun saveViaMediaStore(file: File): Boolean = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$GALLERY_FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: return false
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        logger.i(TAG, "Saved to gallery: $uri")
        true
    }.getOrElse {
        logger.e(TAG, "saveToGallery (MediaStore) failed", it)
        false
    }

    /**
     * Pre-Android-10 gallery save requires WRITE_EXTERNAL_STORAGE, which the app
     * deliberately does not request. The user can still Share the video on these
     * devices. This is a no-op that reports failure so the UI shows a message.
     */
    private fun saveLegacy(file: File): Boolean {
        logger.w(TAG, "saveToGallery unsupported below Android 10 without storage permission")
        return false
    }

    private companion object {
        const val TAG = "VideoShareManager"
        const val PROVIDER_SUFFIX = ".fileprovider"
        const val MIME_TYPE = "video/mp4"
        const val GALLERY_FOLDER = "Liftr"
    }
}
