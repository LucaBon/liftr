package com.lifthelper.domain.model

import java.io.File

/**
 * Metadata about a recorded workout video file awaiting (or after) analysis.
 *
 * @property file The MP4 file on disk
 * @property exerciseType Exercise that was recorded
 * @property timestamp When the recording started (epoch millis)
 * @property sizeBytes Size of the file in bytes
 * @property analyzed Whether post-exercise analysis already completed for this file
 */
data class RecordingInfo(
    val file: File,
    val exerciseType: ExerciseType,
    val timestamp: Long,
    val sizeBytes: Long,
    val analyzed: Boolean = false
)
