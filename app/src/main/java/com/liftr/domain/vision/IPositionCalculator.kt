package com.liftr.domain.vision

import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.CalibrationQuality
import com.liftr.domain.model.PoseResult
import com.liftr.vision.calculator.BodyPartPositions

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
