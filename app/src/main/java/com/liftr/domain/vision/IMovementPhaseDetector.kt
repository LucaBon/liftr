package com.liftr.domain.vision

import com.liftr.domain.model.PoseResult
import com.liftr.vision.tracking.MovementPhase

/**
 * Interface for movement phase detection.
 *
 * Detects the phase of movement (eccentric, concentric, etc.) during an exercise.
 */
interface IMovementPhaseDetector {
    /**
     * Detects the current movement phase from velocity.
     *
     * @param velocity The current velocity in m/s
     * @return The detected movement phase
     */
    fun detectPhase(velocity: Float): MovementPhase

    /**
     * Resets the phase detector state.
     * Should be called when starting a new set.
     */
    fun reset()
}

/**
 * Interface for squat-specific phase detection.
 *
 * Uses position-based detection for more accurate squat rep counting.
 */
interface ISquatPhaseDetector {
    /**
     * Detects the current squat phase from body part positions.
     *
     * @param headY Head position in meters (null if not visible)
     * @param shouldersY Shoulders position in meters (null if not visible)
     * @param hipsY Hips position in meters (null if not visible)
     * @param kneesY Knees position in meters (null if not visible)
     * @param poseResult The full pose result for additional context
     * @return The detected movement phase
     */
    fun detectPhase(
        headY: Float?,
        shouldersY: Float?,
        hipsY: Float?,
        kneesY: Float?,
        poseResult: PoseResult
    ): MovementPhase

    /**
     * Gets debug information about the current position state.
     *
     * @return A string describing the current position metrics
     */
    fun getPositionInfo(): String

    /**
     * Resets the phase detector state.
     * Should be called when starting a new set.
     */
    fun reset()
}
