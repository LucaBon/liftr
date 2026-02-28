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
     * Closes the pose detector and releases resources.
     */
    fun close()
}
