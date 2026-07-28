package com.lifthelper.data.repository

import android.content.Context
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
    @ApplicationContext private val context: Context
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

    override fun getStorageUsedBytes(): Long =
        resolveRecordingsDir().listFiles()?.sumOf { it.length() } ?: 0L

    override fun cleanupOldRecordings(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        resolveRecordingsDir().listFiles()
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
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
        private const val RECORDINGS_DIR = "recordings"
        private const val ANNOTATED_DIR = "annotated"
    }
}
