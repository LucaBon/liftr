package com.liftr.domain.repository

import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.RecordingInfo
import java.io.File

/**
 * Manages storage of workout video recordings used by the post-exercise
 * analysis pipeline (Feature 17).
 *
 * Videos are stored in app-private internal storage so no runtime storage
 * permission is required. They are short-lived: a recording is deleted as soon
 * as analysis succeeds, and any orphans are cleaned up by age.
 */
interface VideoStorageRepository {
    /** Get a new output file for recording a set of [exerciseType]. */
    fun getRecordingFile(exerciseType: ExerciseType, timestamp: Long): File

    /**
     * Output file for the shareable annotated video of [exerciseType]
     * (Feature 09). Stored in a dedicated `annotated/` subdirectory so it is not
     * touched by the raw-recording listing or age-based cleanup.
     */
    fun getAnnotatedFile(exerciseType: ExerciseType, timestamp: Long): File

    /** Get the recordings directory, creating it if needed. */
    fun getRecordingsDir(): File

    /** Delete a recording file. Returns true if it was deleted. */
    fun deleteRecording(file: File): Boolean

    /** Total bytes used by all recordings. */
    fun getStorageUsedBytes(): Long

    /** Delete all recordings older than [maxAgeMs]. */
    fun cleanupOldRecordings(maxAgeMs: Long)

    /** List all recording files with metadata, newest first. */
    fun getAllRecordings(): List<RecordingInfo>
}
