package com.lifthelper.vision.calculator

import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseKeypoint
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.vision.IPositionCalculator
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * Data class to hold positions of different body parts (in pixels)
 */
data class BodyPartPositions(
    val hipsY: Float?,
    val shouldersY: Float?,
    val headY: Float?,
    val kneesY: Float?,
    val hipsX: Float? = null,
    val kneesX: Float? = null,
    val wristsY: Float? = null,
    val wristsX: Float? = null,
    val elbowsY: Float? = null,
    val elbowsX: Float? = null,
    val anklesY: Float? = null,
    val anklesX: Float? = null,
    val shouldersX: Float? = null
)

/**
 * Result of pixels-per-meter calibration with quality indicator
 */
data class CalibrationResult(
    val pixelsPerMeter: Float,
    val quality: com.lifthelper.domain.model.CalibrationQuality
)

class PositionCalculator @Inject constructor(
    private val logger: Logger
) : IPositionCalculator {

    companion object {
        private const val TAG = "PositionCalculator"
    }

    /**
     * Get vertical positions (Y-coordinates) of body parts for squats
     * Averages multiple keypoints for each body part for stability
     *
     * IMPORTANT: Returns Y-coordinates normalized so that larger Y = lower body position
     * (Y-axis pointing downward) regardless of device orientation.
     *
     * @return BodyPartPositions with Y-coordinates in pixels, or null if none are visible
     */
    override fun getBodyPartPositions(poseResult: PoseResult): BodyPartPositions? {
        val landmarks = poseResult.landmarks
        val isPortrait = poseResult.isPortrait

        // Detect Y-axis inversion by checking anatomical ordering on each call
        // This is stateless and thread-safe - no mutable state
        val shouldInvert = detectYAxisInversion(landmarks)

        // Hips: average of left (23) and right (24)
        val leftHip = landmarks.getOrNull(23)
        val rightHip = landmarks.getOrNull(24)
        val hipsY = if (leftHip != null && rightHip != null &&
                       leftHip.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightHip.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftHip.y + rightHip.y) / 2f
        } else if (leftHip != null && leftHip.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftHip.y
        } else if (rightHip != null && rightHip.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightHip.y
        } else null

        // Shoulders: average of left (11) and right (12)
        val leftShoulder = landmarks.getOrNull(11)
        val rightShoulder = landmarks.getOrNull(12)
        val shouldersY = if (leftShoulder != null && rightShoulder != null &&
                            leftShoulder.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightShoulder.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftShoulder.y + rightShoulder.y) / 2f
        } else if (leftShoulder != null && leftShoulder.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftShoulder.y
        } else if (rightShoulder != null && rightShoulder.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightShoulder.y
        } else null

        // Head: average of nose (0), eyes (2, 5), ears (7, 8)
        val headKeypoints = listOfNotNull(
            landmarks.getOrNull(0),  // nose
            landmarks.getOrNull(2),  // left eye
            landmarks.getOrNull(5),  // right eye
            landmarks.getOrNull(7),  // left ear
            landmarks.getOrNull(8)   // right ear
        ).filter { it.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }

        val headY = if (headKeypoints.isNotEmpty()) {
            headKeypoints.map { it.y }.average().toFloat()
        } else null

        // Knees: average of left (25) and right (26)
        val leftKnee = landmarks.getOrNull(25)
        val rightKnee = landmarks.getOrNull(26)
        val kneesY = if (leftKnee != null && rightKnee != null &&
                        leftKnee.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightKnee.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftKnee.y + rightKnee.y) / 2f
        } else if (leftKnee != null && leftKnee.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftKnee.y
        } else if (rightKnee != null && rightKnee.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightKnee.y
        } else null

        // Extract X coordinates for hips and knees (for femur angle calculation)
        val hipsX = if (leftHip != null && rightHip != null &&
                       leftHip.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightHip.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftHip.x + rightHip.x) / 2f
        } else if (leftHip != null && leftHip.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftHip.x
        } else if (rightHip != null && rightHip.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightHip.x
        } else null

        val kneesX = if (leftKnee != null && rightKnee != null &&
                        leftKnee.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightKnee.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftKnee.x + rightKnee.x) / 2f
        } else if (leftKnee != null && leftKnee.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftKnee.x
        } else if (rightKnee != null && rightKnee.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightKnee.x
        } else null

        // Shoulders X coordinate
        val shouldersX = if (leftShoulder != null && rightShoulder != null &&
                            leftShoulder.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightShoulder.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftShoulder.x + rightShoulder.x) / 2f
        } else if (leftShoulder != null && leftShoulder.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftShoulder.x
        } else if (rightShoulder != null && rightShoulder.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightShoulder.x
        } else null

        // Wrists: left (15) and right (16)
        val leftWrist = landmarks.getOrNull(15)
        val rightWrist = landmarks.getOrNull(16)
        val wristsY = if (leftWrist != null && rightWrist != null &&
                         leftWrist.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightWrist.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftWrist.y + rightWrist.y) / 2f
        } else if (leftWrist != null && leftWrist.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftWrist.y
        } else if (rightWrist != null && rightWrist.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightWrist.y
        } else null

        val wristsX = if (leftWrist != null && rightWrist != null &&
                         leftWrist.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightWrist.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftWrist.x + rightWrist.x) / 2f
        } else if (leftWrist != null && leftWrist.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftWrist.x
        } else if (rightWrist != null && rightWrist.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightWrist.x
        } else null

        // Elbows: left (13) and right (14)
        val leftElbow = landmarks.getOrNull(13)
        val rightElbow = landmarks.getOrNull(14)
        val elbowsY = if (leftElbow != null && rightElbow != null &&
                         leftElbow.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightElbow.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftElbow.y + rightElbow.y) / 2f
        } else if (leftElbow != null && leftElbow.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftElbow.y
        } else if (rightElbow != null && rightElbow.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightElbow.y
        } else null

        val elbowsX = if (leftElbow != null && rightElbow != null &&
                         leftElbow.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightElbow.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftElbow.x + rightElbow.x) / 2f
        } else if (leftElbow != null && leftElbow.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftElbow.x
        } else if (rightElbow != null && rightElbow.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightElbow.x
        } else null

        // Ankles: left (27) and right (28)
        val leftAnkle = landmarks.getOrNull(27)
        val rightAnkle = landmarks.getOrNull(28)
        val anklesY = if (leftAnkle != null && rightAnkle != null &&
                         leftAnkle.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightAnkle.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftAnkle.y + rightAnkle.y) / 2f
        } else if (leftAnkle != null && leftAnkle.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftAnkle.y
        } else if (rightAnkle != null && rightAnkle.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightAnkle.y
        } else null

        val anklesX = if (leftAnkle != null && rightAnkle != null &&
                         leftAnkle.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightAnkle.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftAnkle.x + rightAnkle.x) / 2f
        } else if (leftAnkle != null && leftAnkle.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftAnkle.x
        } else if (rightAnkle != null && rightAnkle.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightAnkle.x
        } else null

        // Normalize Y-axis direction for portrait mode
        // In portrait, MediaPipe may have inverted Y-axis, so we need to ensure
        // that larger Y values always mean lower body position
        val normalizedHipsY = normalizeYCoordinate(hipsY, shouldInvert)
        val normalizedShouldersY = normalizeYCoordinate(shouldersY, shouldInvert)
        val normalizedHeadY = normalizeYCoordinate(headY, shouldInvert)
        val normalizedKneesY = normalizeYCoordinate(kneesY, shouldInvert)
        val normalizedWristsY = normalizeYCoordinate(wristsY, shouldInvert)
        val normalizedElbowsY = normalizeYCoordinate(elbowsY, shouldInvert)
        val normalizedAnklesY = normalizeYCoordinate(anklesY, shouldInvert)

        return if (normalizedHipsY != null || normalizedShouldersY != null ||
                   normalizedHeadY != null || normalizedKneesY != null) {
            BodyPartPositions(
                hipsY = normalizedHipsY,
                shouldersY = normalizedShouldersY,
                headY = normalizedHeadY,
                kneesY = normalizedKneesY,
                hipsX = hipsX,
                kneesX = kneesX,
                wristsY = normalizedWristsY,
                wristsX = wristsX,
                elbowsY = normalizedElbowsY,
                elbowsX = elbowsX,
                anklesY = normalizedAnklesY,
                anklesX = anklesX,
                shouldersX = shouldersX
            )
        } else null
    }

    /**
     * Normalize Y-coordinate to ensure consistent direction (larger Y = lower position)
     * regardless of device orientation.
     *
     * @param yValue The Y coordinate to normalize
     * @param shouldInvert Whether to invert the Y-axis (determined by anatomical ordering)
     * @return Normalized Y coordinate
     */
    private fun normalizeYCoordinate(yValue: Float?, shouldInvert: Boolean): Float? {
        if (yValue == null) {
            return null
        }

        // Invert if needed to ensure consistent orientation
        return if (shouldInvert) {
            1.0f - yValue
        } else {
            yValue
        }
    }

    /**
     * Detect if Y-axis is inverted by checking anatomical ordering.
     *
     * In correct orientation: head Y < shoulders Y < hips Y < ankles Y
     * (larger Y values mean lower body position)
     *
     * If ankles have smaller Y than head, the axis is inverted.
     *
     * @return true if Y-axis needs to be inverted (1.0 - y)
     */
    private fun detectYAxisInversion(landmarks: List<PoseKeypoint>): Boolean {
        // Get head keypoints
        val headKeypoints = listOfNotNull(
            landmarks.getOrNull(0),  // nose
            landmarks.getOrNull(7),  // left ear
            landmarks.getOrNull(8)   // right ear
        ).filter { it.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }

        // Get ankle keypoints
        val ankleKeypoints = listOfNotNull(
            landmarks.getOrNull(27), // left ankle
            landmarks.getOrNull(28)  // right ankle
        ).filter { it.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }

        if (headKeypoints.isEmpty() || ankleKeypoints.isEmpty()) {
            // Can't determine, assume no inversion
            return false
        }

        val avgHeadY = headKeypoints.map { it.y }.average().toFloat()
        val avgAnkleY = ankleKeypoints.map { it.y }.average().toFloat()

        // In correct orientation, ankles should have larger Y than head
        // If head Y > ankle Y, the axis is inverted
        val isInverted = avgHeadY > avgAnkleY

        if (isInverted) {
            logger.d(TAG, "Y-axis inversion detected: head Y ($avgHeadY) > ankle Y ($avgAnkleY)")
        }

        return isInverted
    }

    /**
     * Calculate bar position in pixels based on exercise type
     */
    fun getBarPosition(poseResult: PoseResult, exerciseType: ExerciseType): Pair<Float, Float>? {
        val landmarks = poseResult.landmarks

        return when (exerciseType) {
            ExerciseType.FRONT_SQUAT, ExerciseType.BACK_SQUAT, ExerciseType.DEADLIFT -> {
                // Use hip midpoint
                val leftHip = landmarks.getOrNull(23) ?: return null
                val rightHip = landmarks.getOrNull(24) ?: return null
                if (leftHip.visibility < 0.5f || rightHip.visibility < 0.5f) return null
                getMidpoint(leftHip, rightHip)
            }

            ExerciseType.BENCH_PRESS, ExerciseType.CLEAN, ExerciseType.SNATCH -> {
                // Use wrist midpoint
                val leftWrist = landmarks.getOrNull(15) ?: return null
                val rightWrist = landmarks.getOrNull(16) ?: return null
                if (leftWrist.visibility < 0.5f || rightWrist.visibility < 0.5f) return null
                getMidpoint(leftWrist, rightWrist)
            }

            else -> null
        }
    }

    /**
     * Convert normalized position to meters using calibration data
     * Uses height-based calibration which is view-angle independent
     *
     * @param normalizedY Y coordinate in normalized space (0-1 range from pose landmarks)
     */
    override fun pixelsToMeters(
        normalizedY: Float,
        poseResult: PoseResult,
        calibrationData: CalibrationData?
    ): Float {
        // Calculate pixels per meter using detected height
        val calibrationResult = calculatePixelsPerMeter(poseResult, calibrationData)

        // Convert normalized coordinate to pixels, then to meters
        val pixelY = normalizedY * poseResult.imageHeight
        return pixelY / calibrationResult.pixelsPerMeter
    }

    /**
     * Get current calibration quality for user feedback
     */
    override fun getCalibrationQuality(
        poseResult: PoseResult,
        calibrationData: CalibrationData?
    ): com.lifthelper.domain.model.CalibrationQuality {
        return calculatePixelsPerMeter(poseResult, calibrationData).quality
    }

    /**
     * Calculate pixels per meter ratio using detected person height
     * Height is more reliable than shoulder width since it's view-angle independent
     *
     * Uses hierarchical fallback strategy:
     * 1. Full body (head to ankles) - most accurate
     * 2. Torso estimation (head to hips) - uses anthropometric ratios
     * 3. Leg estimation (hips to ankles) - uses anthropometric ratios
     * 4. Stored calibration value - last resort
     */
    private fun calculatePixelsPerMeter(
        poseResult: PoseResult,
        calibrationData: CalibrationData?
    ): CalibrationResult {
        val landmarks = poseResult.landmarks

        // Get head position (average of visible head keypoints)
        val headKeypoints = listOfNotNull(
            landmarks.getOrNull(0),  // nose
            landmarks.getOrNull(2),  // left eye
            landmarks.getOrNull(5),  // right eye
            landmarks.getOrNull(7),  // left ear
            landmarks.getOrNull(8)   // right ear
        ).filter { it.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }

        // Get hip position (average of visible hip keypoints)
        val hipKeypoints = listOfNotNull(
            landmarks.getOrNull(23), // left hip
            landmarks.getOrNull(24)  // right hip
        ).filter { it.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }

        // Get ankle position (average of visible ankle keypoints)
        val ankleKeypoints = listOfNotNull(
            landmarks.getOrNull(27), // left ankle
            landmarks.getOrNull(28)  // right ankle
        ).filter { it.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }

        val hasHead = headKeypoints.isNotEmpty()
        val hasHips = hipKeypoints.isNotEmpty()
        val hasAnkles = ankleKeypoints.isNotEmpty()

        // Check if calibration data is available
        if (calibrationData == null) {
            logger.e(TAG, "❌ No calibration data available")
            return CalibrationResult(
                pixelsPerMeter = 1000f, // Default fallback
                quality = com.lifthelper.domain.model.CalibrationQuality.NONE
            )
        }

        // Strategy 1: Full body visible (head to ankles) - BEST
        if (hasHead && hasAnkles) {
            var headY = headKeypoints.map { it.y }.average().toFloat()
            var ankleY = ankleKeypoints.map { it.y }.average().toFloat()

            // Check if Y-axis is inverted (ankles should be below head, meaning larger Y)
            val isYAxisInverted = ankleY < headY
            if (isYAxisInverted) {
                logger.w(TAG, "⚠️ Y-axis inverted, flipping")
                headY = 1.0f - headY
                ankleY = 1.0f - ankleY
            }

            val detectedHeightNormalized = ankleY - headY  // Now ankleY > headY always
            val detectedHeightPixels = detectedHeightNormalized * poseResult.imageHeight

            logger.d(TAG, "✅ Full body calibration: ${detectedHeightPixels.toInt()}px = ${calibrationData.userHeight}m (headY=$headY, ankleY=$ankleY)")

            return CalibrationResult(
                pixelsPerMeter = detectedHeightPixels / calibrationData.userHeight,
                quality = com.lifthelper.domain.model.CalibrationQuality.OPTIMAL
            )
        }

        // Strategy 2: Upper body only (head to hips) - estimate full height
        if (hasHead && hasHips) {
            var headY = headKeypoints.map { it.y }.average().toFloat()
            var hipY = hipKeypoints.map { it.y }.average().toFloat()

            // Check if Y-axis is inverted
            val isYAxisInverted = hipY < headY
            if (isYAxisInverted) {
                headY = 1.0f - headY
                hipY = 1.0f - hipY
            }

            val torsoHeightNormalized = hipY - headY  // hipY > headY after correction
            val torsoHeightPixels = torsoHeightNormalized * poseResult.imageHeight

            // Anthropometric ratio: torso (head to hip) ≈ 52% of total height
            val estimatedFullHeightPixels = torsoHeightPixels / 0.52f

            logger.w(TAG, "⚠️ Partial body (torso only): estimated ${estimatedFullHeightPixels.toInt()}px = ${calibrationData.userHeight}m")

            return CalibrationResult(
                pixelsPerMeter = estimatedFullHeightPixels / calibrationData.userHeight,
                quality = com.lifthelper.domain.model.CalibrationQuality.ESTIMATED
            )
        }

        // Strategy 3: Lower body only (hips to ankles) - estimate full height
        if (hasHips && hasAnkles) {
            var hipY = hipKeypoints.map { it.y }.average().toFloat()
            var ankleY = ankleKeypoints.map { it.y }.average().toFloat()

            // Check if Y-axis is inverted
            val isYAxisInverted = ankleY < hipY
            if (isYAxisInverted) {
                hipY = 1.0f - hipY
                ankleY = 1.0f - ankleY
            }

            val legHeightNormalized = ankleY - hipY  // ankleY > hipY after correction
            val legHeightPixels = legHeightNormalized * poseResult.imageHeight

            // Anthropometric ratio: legs (hip to ankle) ≈ 48% of total height
            val estimatedFullHeightPixels = legHeightPixels / 0.48f

            logger.w(TAG, "⚠️ Partial body (legs only): estimated ${estimatedFullHeightPixels.toInt()}px = ${calibrationData.userHeight}m")

            return CalibrationResult(
                pixelsPerMeter = estimatedFullHeightPixels / calibrationData.userHeight,
                quality = com.lifthelper.domain.model.CalibrationQuality.ESTIMATED
            )
        }

        // Strategy 4: Fall back to stored calibration (no valid segments detected)
        logger.e(TAG, "❌ Cannot detect body segments, using stored calibration: ${calibrationData.pixelsPerMeter}px/m")

        return CalibrationResult(
            pixelsPerMeter = calibrationData.pixelsPerMeter,
            quality = com.lifthelper.domain.model.CalibrationQuality.FALLBACK
        )
    }

    private fun getMidpoint(p1: PoseKeypoint, p2: PoseKeypoint): Pair<Float, Float> {
        return Pair((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
    }
}
