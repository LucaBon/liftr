package com.liftr.domain.vision

import com.liftr.domain.model.PoseResult
import kotlinx.coroutines.flow.Flow

/**
 * Per-frame pose detections for one recorded video.
 *
 * Hides the platform video decoder and pose model from the domain layer.
 */
interface VideoPoseSource {
    /** Number of frames in the video; populated once [poses] starts emitting. */
    val totalFrames: Int

    /** Slow-motion factor the timestamps were corrected by; 1 when none was found. Valid once [poses] has run. */
    val timeScale: Float get() = 1f

    /**
     * One decoded frame: its time, its pose if it was analyzed and one was
     * found, and the barbell plate if the source tracks it and found it.
     */
    data class Frame(val timestampMs: Long, val pose: PoseResult?, val plate: PlateObservation? = null)

    /**
     * Cold flow with one element per decoded frame, in presentation order.
     * Pose detection runs only on frames [include] accepts; the others are
     * emitted with a null pose. Each collection decodes the video again with a
     * fresh pose model, so a video can be analyzed in several passes.
     */
    fun poses(include: (timestampUs: Long) -> Boolean = { true }): Flow<Frame>

    /** Releases the decoder. */
    fun close()
}
