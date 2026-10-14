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

    /** One decoded frame: its time, and its pose if it was analyzed and one was found. */
    data class Frame(val timestampMs: Long, val pose: PoseResult?)

    /**
     * Cold flow with one element per decoded frame, in presentation order.
     * Pose detection runs only on frames [include] accepts; the others are
     * emitted with a null pose. Each collection decodes the video again with a
     * fresh pose model, so a video can be analyzed in several passes.
     */
    fun poses(include: (timestampMs: Long) -> Boolean = { true }): Flow<Frame>

    /** Releases the decoder. */
    fun close()
}
