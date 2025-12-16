package com.lifthelper.domain.vision

import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.CalibrationQuality
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.vision.calculator.BodyPartPositions

/**
 * Interface for position calculation operations.
 *
 * Extracts body part positions and converts between pixel and metric coordinates.
 */
interface IPositionCalculator {
    /**
     * Extracts body part positions from a pose result.
     *
     * @param poseResult The detected pose
     * @return BodyPartPositions if extraction was successful, null otherwise
     */
    fun getBodyPartPositions(poseResult: PoseResult): BodyPartPositions?

    /**
     * Converts a pixel position to meters.
     *
     * @param pixelValue The pixel value to convert
     * @param poseResult The pose result for context
     * @param calibrationData The calibration data for conversion
     * @return The value in meters
     */
    fun pixelsToMeters(
        pixelValue: Float,
        poseResult: PoseResult,
        calibrationData: CalibrationData?
    ): Float

    /**
     * Assesses the quality of calibration based on pose visibility.
     *
     * @param poseResult The detected pose
     * @param calibrationData The current calibration data (may be null)
     * @return The calibration quality assessment
     */
    fun getCalibrationQuality(
        poseResult: PoseResult,
        calibrationData: CalibrationData?
    ): CalibrationQuality
}
