package com.lifthelper.data.repository

import android.content.Context
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.RecordingInfo
import com.lifthelper.domain.repository.VideoStorageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores workout recordings in `filesDir/recordings/`. Filenames encode the
 * exercise type and start timestamp: `{exercise}_{timestamp}.mp4`.
 */
@Singleton
class VideoStorageRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) : VideoStorageRepository {

    private fun resolveRecordingsDir(): File =
        File(context.filesDir, RECORDINGS_DIR).also { it.mkdirs() }

    private fun resolveAnnotatedDir(): File =
        File(resolveRecordingsDir(), ANNOTATED_DIR).also { it.mkdirs() }

    override fun getRecordingFile(exerciseType: ExerciseType, timestamp: Long): File {
        val name = "${exerciseType.name.lowercase()}_$timestamp.mp4"
        return File(resolveRecordingsDir(), name)
    }

    override fun getAnnotatedFile(exerciseType: ExerciseType, timestamp: Long): File {
        val name = "lifthelper_${exerciseType.name.lowercase()}_$timestamp.mp4"
        return File(resolveAnnotatedDir(), name)
    }

    override fun getRecordingsDir(): File = resolveRecordingsDir()

    override fun deleteRecording(file: File): Boolean =
        runCatching { file.exists() && file.delete() }.getOrDefault(false)

    /**
     * Total bytes held by recordings, including the `annotated/` subdirectory.
     * A flat `listFiles()` sum reported only the raw recordings and silently
     * understated usage by the size of every annotated export.
     */
    override fun getStorageUsedBytes(): Long =
        resolveRecordingsDir()
            .walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

    /**
     * Deletes recordings older than [maxAgeMs].
     *
     * Walks recursively: `listFiles()` returns the `annotated/` directory too,
     * and `File.delete()` on a non-empty directory silently returns false, so
     * annotated exports were never reclaimable. Only files are removed — the
     * directories themselves are kept so nothing has to recreate them.
     *
     * Age is taken from the file's last-modified time, so a recording that is
     * still being written or analysed (seconds old) can never be caught by a
     * sane retention window.
     */
    override fun cleanupOldRecordings(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var deletedCount = 0
        var freedBytes = 0L

        resolveRecordingsDir()
            .walkBottomUp()
            .filter { it.isFile && it.lastModified() < cutoff }
            .forEach { file ->
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    deletedCount++
                    freedBytes += size
                } else {
                    logger.w(TAG, "Could not delete stale recording: ${file.name}")
                }
            }

        if (deletedCount > 0) {
            logger.i(TAG, "Retention: removed $deletedCount recording(s), freed ${freedBytes / 1_048_576} MB")
        }
    }

    override fun getAllRecordings(): List<RecordingInfo> {
        return resolveRecordingsDir().listFiles()
            ?.filter { it.extension == "mp4" }
            ?.map { file ->
                // Names are "{exercise}_{timestamp}" and exercise names contain
                // underscores themselves (front_squat, bench_press), so the split
                // must be anchored at the LAST underscore. Splitting at the first
                // one yields "front" — not a valid ExerciseType — and every such
                // recording silently fell back to BACK_SQUAT with a wrong date.
                val name = file.nameWithoutExtension
                val exerciseType = runCatching {
                    ExerciseType.valueOf(name.substringBeforeLast('_').uppercase())
                }.getOrDefault(ExerciseType.BACK_SQUAT)
                val timestamp = name.substringAfterLast('_').toLongOrNull()
                    ?: file.lastModified()
                RecordingInfo(
                    file = file,
                    exerciseType = exerciseType,
                    timestamp = timestamp,
                    sizeBytes = file.length()
                )
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    companion object {
        private const val TAG = "VideoStorageRepository"
        private const val RECORDINGS_DIR = "recordings"
        private const val ANNOTATED_DIR = "annotated"
    }
}
