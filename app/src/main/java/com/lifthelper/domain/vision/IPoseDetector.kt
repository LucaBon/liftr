package com.lifthelper.domain.vision

import com.lifthelper.domain.model.CameraFrame
import com.lifthelper.domain.model.PoseResult

/**
 * Interface for pose detection operations.
 *
 * Abstracts the pose detection implementation to allow for testing
 * and potential alternative implementations.
 */
interface IPoseDetector {
    /**
     * Detects pose from a camera frame.
     *
     * @param frame The camera frame to process
     * @return PoseResult if a pose was detected, null otherwise
     */
    fun detectPose(frame: CameraFrame): PoseResult?

    /**
     * Switches the pose detection model.
     *
     * @param modelPath Path to the model file to use
     */
    fun switchModel(modelPath: String)

    /**
     * Gets the currently active model path.
     *
     * @return The path to the current model
     */
    fun getCurrentModel(): String

    /**
     * Whether pose detection is usable on this device. Returns false when the
     * underlying ML runtime (native library / model) could not be loaded — e.g.
     * on emulator ABIs that lack the MediaPipe vision library. When false,
     * [detectPose] is a no-op and callers should surface a friendly message
     * rather than attempting camera-based tracking.
     */
    fun isAvailable(): Boolean = true

    /**
     * Closes the pose detector and releases resources.
     */
    fun close()
}
