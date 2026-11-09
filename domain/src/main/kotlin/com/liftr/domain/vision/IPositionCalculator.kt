package com.liftr.domain.vision

import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.CalibrationQuality
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.BodyPartPositions

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
     * Converts a vertical position to meters. Despite the name, the input is
     * normalized y (0..1 of the image height); the implementation scales it to
     * pixels itself, so callers must not pre-multiply by the image height.
     *
     * @param pixelValue The normalized y coordinate to convert
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
     * Forgets the scale locked from earlier frames. Call at the start of every
     * set (live or video): the scale is measured from the standing person and
     * then held, so it must not carry over to another set or camera position.
     */
    fun resetScale() {}

    /**
     * Holds one scale for a whole recorded set, measured where the lifter
     * stands still, instead of following the frames as they come. Only a
     * recording can do this, having every frame up front. Stays in force until
     * [resetScale]; does nothing when the lifter never stood still.
     */
    fun lockScaleToStanding(poses: List<PoseResult>, calibrationData: CalibrationData?) {}

    /**
     * A calculator with its own scale state. The app shares one calculator
     * between live sets and background video analysis; an analysis takes its
     * own so a live set's [resetScale] (or frames) cannot shift its scale
     * midway. A stateless implementation may return itself.
     */
    fun newSession(): IPositionCalculator = this

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
