package com.lifthelper.domain.vision

import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.VelocityData

/**
 * Interface for velocity calculation operations.
 *
 * Calculates velocity and power metrics from pose data and calibration information.
 */
interface IVelocityCalculator {
    /**
     * Calculates velocity data from a pose result.
     *
     * @param poseResult The detected pose
     * @param exerciseType The type of exercise being performed
     * @param calibrationData The calibration data for metric conversion
     * @param timestamp The timestamp of this measurement
     * @return VelocityData if calculation was successful, null otherwise
     */
    fun calculate(
        poseResult: PoseResult,
        exerciseType: ExerciseType,
        calibrationData: CalibrationData,
        timestamp: Long
    ): VelocityData?

    /**
     * Resets the velocity calculator state.
     * Should be called when starting a new set.
     */
    fun reset()
}
