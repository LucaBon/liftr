package com.liftr.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.liftr.domain.model.ExportResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes an [ExportResult] to the app-private cache directory and launches
 * the Android share sheet so the user can route it to Drive, email, Files, etc.
 */
@Singleton
class FileExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun exportAndShare(result: ExportResult, chooserTitle: String) {
        val exportDir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val file = File(exportDir, result.fileName)
        file.writeText(result.content)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}$PROVIDER_SUFFIX",
            file
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = result.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, result.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private companion object {
        const val EXPORT_DIR = "exports"
        const val PROVIDER_SUFFIX = ".fileprovider"
    }
}
