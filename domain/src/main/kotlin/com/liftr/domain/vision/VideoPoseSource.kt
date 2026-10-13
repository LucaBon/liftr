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

    /**
     * Cold flow with one element per decoded frame, in presentation order:
     * the detected pose, or null when no pose was found in that frame.
     */
    fun poses(): Flow<PoseResult?>

    /** Releases the decoder. Does not close a pose model owned by the caller. */
    fun close()
}
