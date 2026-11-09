package com.liftr.domain.vision

import com.liftr.domain.logging.Logger
import com.liftr.domain.model.BodyPartPositions
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.CalibrationQuality
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult

/**
 * Body part positions and the pixel-to-meter scale, from pose landmarks alone.
 * Pure Kotlin so the app and the replay tests run the same math.
 */
open class BodyPositionCalculator(
    private val logger: Logger
) : IPositionCalculator {

    /** Result of pixels-per-meter calibration with quality indicator. */
    data class CalibrationResult(
        val pixelsPerMeter: Float,
        val quality: CalibrationQuality
    )

    /**
     * Last pixels-per-meter value that passed validation, used when a frame
     * produces a degenerate one. Seeded with the same default the no-calibration
     * path returns.
     */
    @Volatile
    private var lastGoodPixelsPerMeter = DEFAULT_PIXELS_PER_METER

    /**
     * Y positions of the body parts (larger Y = lower), averaging left and right
     * where both are visible; X values in image-height units.
     *
     * @return null if none of hips, shoulders, head or knees is visible
     */
    override fun getBodyPartPositions(poseResult: PoseResult): BodyPartPositions? {
        val landmarks = poseResult.landmarks
        val shouldInvert = detectYAxisInversion(landmarks)

        fun pair(left: Int, right: Int, axis: (PoseKeypoint) -> Float): Float? =
            visible(landmarks, left, right).takeIf { it.isNotEmpty() }?.map(axis)?.average()?.toFloat()
        fun y(left: Int, right: Int) = pair(left, right) { it.y }?.let { if (shouldInvert) 1f - it else it }
        fun x(left: Int, right: Int) = pair(left, right) { it.x }?.times(poseResult.aspectScaleX)

        val hipsY = y(PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP)
        val shouldersY = y(PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER)
        val headY = visible(landmarks, *HEAD).takeIf { it.isNotEmpty() }
            ?.map { it.y }?.average()?.toFloat()?.let { if (shouldInvert) 1f - it else it }
        val kneesY = y(PoseResult.LEFT_KNEE, PoseResult.RIGHT_KNEE)

        if (hipsY == null && shouldersY == null && headY == null && kneesY == null) return null
        // X values go out in image-height units so they share Y's unit
        // (see PoseResult.aspectScaleX); consumers combine them with Y in
        // angles and convert them to meters with the Y-derived scale.
        return BodyPartPositions(
            hipsY = hipsY,
            shouldersY = shouldersY,
            headY = headY,
            kneesY = kneesY,
            hipsX = x(PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP),
            kneesX = x(PoseResult.LEFT_KNEE, PoseResult.RIGHT_KNEE),
            wristsY = y(PoseResult.LEFT_WRIST, PoseResult.RIGHT_WRIST),
            wristsX = x(PoseResult.LEFT_WRIST, PoseResult.RIGHT_WRIST),
            elbowsY = y(PoseResult.LEFT_ELBOW, PoseResult.RIGHT_ELBOW),
            elbowsX = x(PoseResult.LEFT_ELBOW, PoseResult.RIGHT_ELBOW),
            anklesY = y(PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE),
            anklesX = x(PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE),
            shouldersX = x(PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER)
        )
    }

    /** True when the ankles read above the head, which the old heuristic took as a flipped Y axis. */
    private fun detectYAxisInversion(landmarks: List<PoseKeypoint>): Boolean {
        val head = visible(landmarks, PoseResult.NOSE, PoseResult.LEFT_EAR, PoseResult.RIGHT_EAR)
        val ankles = visible(landmarks, PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE)
        if (head.isEmpty() || ankles.isEmpty()) return false
        return head.map { it.y }.average() > ankles.map { it.y }.average()
    }

    /**
     * Converts normalized y to meters: scaled to pixels by the image height,
     * then divided by the pixels-per-meter scale.
     */
    override fun pixelsToMeters(
        pixelValue: Float,
        poseResult: PoseResult,
        calibrationData: CalibrationData?
    ): Float {
        val calibrationResult = calculatePixelsPerMeter(poseResult, calibrationData)
        return pixelValue * poseResult.imageHeight / calibrationResult.pixelsPerMeter
    }

    override fun getCalibrationQuality(
        poseResult: PoseResult,
        calibrationData: CalibrationData?
    ): CalibrationQuality = calculatePixelsPerMeter(poseResult, calibrationData).quality

    /**
     * Pixels per meter from the detected person height, rejecting degenerate
     * values: every strategy divides a detected pixel height by the user's
     * height, and both can be zero. The resulting 0 / Infinity / NaN would
     * propagate through pixelsToMeters into the smoothing filters, where a
     * single NaN sticks and silently kills rep counting for the rest of the set.
     */
    fun calculatePixelsPerMeter(
        poseResult: PoseResult,
        calibrationData: CalibrationData?
    ): CalibrationResult {
        val result = calculatePixelsPerMeterUnchecked(poseResult, calibrationData)
        val ppm = result.pixelsPerMeter
        if (!ppm.isFinite() || ppm < MIN_PIXELS_PER_METER) {
            logger.w(TAG, "Rejecting implausible pixelsPerMeter=$ppm, using last known good")
            return CalibrationResult(lastGoodPixelsPerMeter, CalibrationQuality.FALLBACK)
        }
        lastGoodPixelsPerMeter = ppm
        return result
    }

    /**
     * Hierarchical fallback:
     * 1. Full body (head to ankles) - most accurate
     * 2. Torso (head to hips) - anthropometric ratio
     * 3. Legs (hips to ankles) - anthropometric ratio
     * 4. Stored calibration value
     */
    private fun calculatePixelsPerMeterUnchecked(
        poseResult: PoseResult,
        calibrationData: CalibrationData?
    ): CalibrationResult {
        val landmarks = poseResult.landmarks
        val head = visible(landmarks, *HEAD)
        val hips = visible(landmarks, PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP)
        val ankles = visible(landmarks, PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE)

        if (calibrationData == null) {
            logger.e(TAG, "No calibration data available")
            return CalibrationResult(DEFAULT_PIXELS_PER_METER, CalibrationQuality.NONE)
        }

        fun span(top: List<PoseKeypoint>, bottom: List<PoseKeypoint>): Float {
            var topY = top.map { it.y }.average().toFloat()
            var bottomY = bottom.map { it.y }.average().toFloat()
            if (bottomY < topY) {
                topY = 1f - topY
                bottomY = 1f - bottomY
            }
            return (bottomY - topY) * poseResult.imageHeight
        }

        if (head.isNotEmpty() && ankles.isNotEmpty()) {
            return CalibrationResult(span(head, ankles) / calibrationData.userHeight, CalibrationQuality.OPTIMAL)
        }
        if (head.isNotEmpty() && hips.isNotEmpty()) {
            return CalibrationResult(
                span(head, hips) / TORSO_SHARE_OF_HEIGHT / calibrationData.userHeight, CalibrationQuality.ESTIMATED
            )
        }
        if (hips.isNotEmpty() && ankles.isNotEmpty()) {
            return CalibrationResult(
                span(hips, ankles) / LEGS_SHARE_OF_HEIGHT / calibrationData.userHeight, CalibrationQuality.ESTIMATED
            )
        }
        return CalibrationResult(calibrationData.pixelsPerMeter, CalibrationQuality.FALLBACK)
    }

    private fun visible(landmarks: List<PoseKeypoint>, vararg ids: Int): List<PoseKeypoint> =
        ids.map { landmarks.getOrNull(it) }.filterNotNull().filter { it.visibility >= PoseKeypoint.VISIBILITY_THRESHOLD }

    companion object {
        private const val TAG = "PositionCalculator"

        /** Fallback scale when nothing can be measured (1000 px/m). */
        const val DEFAULT_PIXELS_PER_METER = 1000f

        /**
         * Below this the value cannot describe a person in frame; treating it as
         * real would blow up every pixel-to-meter conversion that divides by it.
         */
        private const val MIN_PIXELS_PER_METER = 1f

        /** Head to hip is about 52% of standing height. */
        private const val TORSO_SHARE_OF_HEIGHT = 0.52f

        /** Hip to ankle is about 48% of standing height. */
        private const val LEGS_SHARE_OF_HEIGHT = 0.48f

        private val HEAD = intArrayOf(
            PoseResult.NOSE, PoseResult.LEFT_EYE, PoseResult.RIGHT_EYE, PoseResult.LEFT_EAR, PoseResult.RIGHT_EAR
        )
    }
}
