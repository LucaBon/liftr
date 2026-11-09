package com.liftr.vision.calculator

import com.liftr.domain.model.BodyPartPositions
import com.liftr.domain.logging.Logger
import com.liftr.domain.exercise.normalizedXY
import com.liftr.domain.exercise.trackingPoint
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.vision.IPositionCalculator
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Result of pixels-per-meter calibration with quality indicator
 */
data class CalibrationResult(
    val pixelsPerMeter: Float,
    val quality: com.liftr.domain.model.CalibrationQuality
)

class PositionCalculator @Inject constructor(
    private val logger: Logger
) : IPositionCalculator {

    /**
     * Last pixels-per-meter value that passed validation, used when a frame
     * produces a degenerate one. Seeded with the same default the no-calibration
     * path returns.
     */
    @Volatile
    private var lastGoodPixelsPerMeter = DEFAULT_PIXELS_PER_METER

    companion object {
        private const val TAG = "PositionCalculator"

        /** Fallback scale when nothing can be measured (1000 px/m). */
        private const val DEFAULT_PIXELS_PER_METER = 1000f

        /**
         * Below this the value cannot describe a person in frame; treating it as
         * real would blow up every pixel→meter conversion that divides by it.
         */
        private const val MIN_PIXELS_PER_METER = 1f

        /** tan(30°): torso tilt below which the lifter counts as lying down. */
        private const val LYING_MAX_TILT_TAN = 0.577f

        private val HEAD_INDICES = listOf(0, 2, 5, 7, 8)
        private val HIP_INDICES = listOf(23, 24)
        private val KNEE_INDICES = listOf(25, 26)
        private val ANKLE_INDICES = listOf(27, 28)
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
        // A lifter lying down has head and ankles at about the same height, so
        // their order says nothing about the image being upside down.
        val shouldInvert = !isLyingDown(poseResult) && detectYAxisInversion(landmarks)

        // Hips: average of left (23) and right (24)
        val leftHip = landmarks.getOrNull(23)
        val rightHip = landmarks.getOrNull(24)
        val hipsY = if (leftHip != null && rightHip != null &&
                       leftHip.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightHip.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftHip.y + rightHip.y) / 2f
        } else if (leftHip != null && leftHip.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftHip.y
        } else if (rightHip != null && rightHip.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightHip.y
        } else null

        // Shoulders: average of left (11) and right (12)
        val leftShoulder = landmarks.getOrNull(11)
        val rightShoulder = landmarks.getOrNull(12)
        val shouldersY = if (leftShoulder != null && rightShoulder != null &&
                            leftShoulder.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightShoulder.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftShoulder.y + rightShoulder.y) / 2f
        } else if (leftShoulder != null && leftShoulder.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftShoulder.y
        } else if (rightShoulder != null && rightShoulder.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightShoulder.y
        } else null

        // Head: average of nose (0), eyes (2, 5), ears (7, 8)
        val headKeypoints = listOfNotNull(
            landmarks.getOrNull(0),  // nose
            landmarks.getOrNull(2),  // left eye
            landmarks.getOrNull(5),  // right eye
            landmarks.getOrNull(7),  // left ear
            landmarks.getOrNull(8)   // right ear
        ).filter { it.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }

        val headY = if (headKeypoints.isNotEmpty()) {
            headKeypoints.map { it.y }.average().toFloat()
        } else null

        // Knees: average of left (25) and right (26)
        val leftKnee = landmarks.getOrNull(25)
        val rightKnee = landmarks.getOrNull(26)
        val kneesY = if (leftKnee != null && rightKnee != null &&
                        leftKnee.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightKnee.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftKnee.y + rightKnee.y) / 2f
        } else if (leftKnee != null && leftKnee.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftKnee.y
        } else if (rightKnee != null && rightKnee.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightKnee.y
        } else null

        // Extract X coordinates for hips and knees (for femur angle calculation)
        val hipsX = if (leftHip != null && rightHip != null &&
                       leftHip.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightHip.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftHip.x + rightHip.x) / 2f
        } else if (leftHip != null && leftHip.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftHip.x
        } else if (rightHip != null && rightHip.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightHip.x
        } else null

        val kneesX = if (leftKnee != null && rightKnee != null &&
                        leftKnee.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightKnee.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftKnee.x + rightKnee.x) / 2f
        } else if (leftKnee != null && leftKnee.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftKnee.x
        } else if (rightKnee != null && rightKnee.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightKnee.x
        } else null

        // Shoulders X coordinate
        val shouldersX = if (leftShoulder != null && rightShoulder != null &&
                            leftShoulder.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightShoulder.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftShoulder.x + rightShoulder.x) / 2f
        } else if (leftShoulder != null && leftShoulder.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftShoulder.x
        } else if (rightShoulder != null && rightShoulder.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightShoulder.x
        } else null

        // Wrists: left (15) and right (16)
        val leftWrist = landmarks.getOrNull(15)
        val rightWrist = landmarks.getOrNull(16)
        val wristsY = if (leftWrist != null && rightWrist != null &&
                         leftWrist.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightWrist.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftWrist.y + rightWrist.y) / 2f
        } else if (leftWrist != null && leftWrist.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftWrist.y
        } else if (rightWrist != null && rightWrist.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightWrist.y
        } else null

        val wristsX = if (leftWrist != null && rightWrist != null &&
                         leftWrist.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightWrist.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftWrist.x + rightWrist.x) / 2f
        } else if (leftWrist != null && leftWrist.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftWrist.x
        } else if (rightWrist != null && rightWrist.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightWrist.x
        } else null

        // Elbows: left (13) and right (14)
        val leftElbow = landmarks.getOrNull(13)
        val rightElbow = landmarks.getOrNull(14)
        val elbowsY = if (leftElbow != null && rightElbow != null &&
                         leftElbow.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightElbow.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftElbow.y + rightElbow.y) / 2f
        } else if (leftElbow != null && leftElbow.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftElbow.y
        } else if (rightElbow != null && rightElbow.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightElbow.y
        } else null

        val elbowsX = if (leftElbow != null && rightElbow != null &&
                         leftElbow.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightElbow.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftElbow.x + rightElbow.x) / 2f
        } else if (leftElbow != null && leftElbow.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftElbow.x
        } else if (rightElbow != null && rightElbow.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightElbow.x
        } else null

        // Ankles: left (27) and right (28)
        val leftAnkle = landmarks.getOrNull(27)
        val rightAnkle = landmarks.getOrNull(28)
        val anklesY = if (leftAnkle != null && rightAnkle != null &&
                         leftAnkle.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightAnkle.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftAnkle.y + rightAnkle.y) / 2f
        } else if (leftAnkle != null && leftAnkle.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftAnkle.y
        } else if (rightAnkle != null && rightAnkle.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            rightAnkle.y
        } else null

        val anklesX = if (leftAnkle != null && rightAnkle != null &&
                         leftAnkle.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD && rightAnkle.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            (leftAnkle.x + rightAnkle.x) / 2f
        } else if (leftAnkle != null && leftAnkle.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
            leftAnkle.x
        } else if (rightAnkle != null && rightAnkle.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD) {
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
            // X values go out in image-height units so they share Y's unit
            // (see PoseResult.aspectScaleX); consumers combine them with Y in
            // angles and convert them to meters with the Y-derived scale.
            val sx = poseResult.aspectScaleX
            BodyPartPositions(
                hipsY = normalizedHipsY,
                shouldersY = normalizedShouldersY,
                headY = normalizedHeadY,
                kneesY = normalizedKneesY,
                hipsX = hipsX?.times(sx),
                kneesX = kneesX?.times(sx),
                wristsY = normalizedWristsY,
                wristsX = wristsX?.times(sx),
                elbowsY = normalizedElbowsY,
                elbowsX = elbowsX?.times(sx),
                anklesY = normalizedAnklesY,
                anklesX = anklesX?.times(sx),
                shouldersX = shouldersX?.times(sx)
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
        ).filter { it.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }

        // Get ankle keypoints
        val ankleKeypoints = listOfNotNull(
            landmarks.getOrNull(27), // left ankle
            landmarks.getOrNull(28)  // right ankle
        ).filter { it.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }

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
     * Tracked bar position in normalized image coordinates for the exercise type
     */
    fun getBarPosition(poseResult: PoseResult, exerciseType: ExerciseType): Pair<Float, Float>? =
        normalizedXY(poseResult, trackingPoint(exerciseType))

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
    ): com.liftr.domain.model.CalibrationQuality {
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
        val result = calculatePixelsPerMeterUnchecked(poseResult, calibrationData)
        val ppm = result.pixelsPerMeter

        // Every strategy below divides a detected pixel height by the user's
        // height, and both can legitimately be zero: degenerate landmarks put
        // head and ankle at the same Y, and stored calibration may carry a bad
        // height. The resulting 0 / Infinity / NaN would then propagate through
        // pixelsToMeters into the smoothing filters, where a single NaN sticks
        // permanently and silently kills rep counting for the rest of the set.
        if (!ppm.isFinite() || ppm < MIN_PIXELS_PER_METER) {
            logger.w(TAG, "Rejecting implausible pixelsPerMeter=$ppm, using last known good")
            return CalibrationResult(
                pixelsPerMeter = lastGoodPixelsPerMeter,
                quality = com.liftr.domain.model.CalibrationQuality.FALLBACK
            )
        }

        lastGoodPixelsPerMeter = ppm
        return result
    }

    private fun calculatePixelsPerMeterUnchecked(
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
        ).filter { it.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }

        // Get hip position (average of visible hip keypoints)
        val hipKeypoints = listOfNotNull(
            landmarks.getOrNull(23), // left hip
            landmarks.getOrNull(24)  // right hip
        ).filter { it.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }

        // Get ankle position (average of visible ankle keypoints)
        val ankleKeypoints = listOfNotNull(
            landmarks.getOrNull(27), // left ankle
            landmarks.getOrNull(28)  // right ankle
        ).filter { it.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }

        val hasHead = headKeypoints.isNotEmpty()
        val hasHips = hipKeypoints.isNotEmpty()
        val hasAnkles = ankleKeypoints.isNotEmpty()

        // Check if calibration data is available
        if (calibrationData == null) {
            logger.e(TAG, "❌ No calibration data available")
            return CalibrationResult(
                pixelsPerMeter = 1000f, // Default fallback
                quality = com.liftr.domain.model.CalibrationQuality.NONE
            )
        }

        // Lying lifter (bench press): the body runs across the frame, so the
        // vertical spans below measure only the drop from bench to floor, a
        // fraction of the body that also wobbles with every landmark jitter.
        // Measure the body along its own segments instead.
        lyingBodyCalibration(poseResult, calibrationData)?.let { return it }

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
                quality = com.liftr.domain.model.CalibrationQuality.OPTIMAL
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
                quality = com.liftr.domain.model.CalibrationQuality.ESTIMATED
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
                quality = com.liftr.domain.model.CalibrationQuality.ESTIMATED
            )
        }

        // Strategy 4: Fall back to stored calibration (no valid segments detected)
        logger.e(TAG, "❌ Cannot detect body segments, using stored calibration: ${calibrationData.pixelsPerMeter}px/m")

        return CalibrationResult(
            pixelsPerMeter = calibrationData.pixelsPerMeter,
            quality = com.liftr.domain.model.CalibrationQuality.FALLBACK
        )
    }

    /**
     * Scale for a lifter lying down, or null when the body is not horizontal.
     *
     * Body length is the pixel path head → hips → knees → ankles, which matches
     * the head-to-ankle span of a standing body however the knees are bent.
     * Without knees and ankles it falls back to the torso, using the same 52%
     * ratio as the upright torso estimate.
     */
    private fun lyingBodyCalibration(
        poseResult: PoseResult,
        calibrationData: CalibrationData
    ): CalibrationResult? {
        val landmarks = poseResult.landmarks
        if (!isLyingDown(poseResult)) return null

        val head = averagePoint(landmarks, HEAD_INDICES) ?: return null
        val hips = averagePoint(landmarks, HIP_INDICES) ?: return null
        val knees = averagePoint(landmarks, KNEE_INDICES)
        val ankles = averagePoint(landmarks, ANKLE_INDICES)

        val torso = pixelDistance(head, hips, poseResult)
        return if (knees != null && ankles != null) {
            val bodyPixels = torso + pixelDistance(hips, knees, poseResult) +
                pixelDistance(knees, ankles, poseResult)
            logger.d(TAG, "Lying body calibration: ${bodyPixels.toInt()}px = ${calibrationData.userHeight}m")
            CalibrationResult(
                pixelsPerMeter = bodyPixels / calibrationData.userHeight,
                quality = com.liftr.domain.model.CalibrationQuality.OPTIMAL
            )
        } else {
            val bodyPixels = torso / 0.52f
            logger.w(TAG, "Lying body, torso only: estimated ${bodyPixels.toInt()}px = ${calibrationData.userHeight}m")
            CalibrationResult(
                pixelsPerMeter = bodyPixels / calibrationData.userHeight,
                quality = com.liftr.domain.model.CalibrationQuality.ESTIMATED
            )
        }
    }

    /**
     * True when the torso (head to hips) lies within [LYING_MAX_TILT_DEG] of
     * horizontal. The margin keeps a squat's forward lean, which rarely gets
     * that flat, on the upright scale.
     */
    private fun isLyingDown(poseResult: PoseResult): Boolean {
        val head = averagePoint(poseResult.landmarks, HEAD_INDICES) ?: return false
        val hips = averagePoint(poseResult.landmarks, HIP_INDICES) ?: return false
        val dx = abs(head.first - hips.first) * poseResult.imageWidth
        val dy = abs(head.second - hips.second) * poseResult.imageHeight
        return dx > 0f && dy < dx * LYING_MAX_TILT_TAN
    }

    private fun averagePoint(landmarks: List<PoseKeypoint>, indices: List<Int>): Pair<Float, Float>? {
        val visible = indices.mapNotNull { landmarks.getOrNull(it) }
            .filter { it.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }
        if (visible.isEmpty()) return null
        return visible.map { it.x }.average().toFloat() to visible.map { it.y }.average().toFloat()
    }

    private fun pixelDistance(a: Pair<Float, Float>, b: Pair<Float, Float>, poseResult: PoseResult): Float =
        hypot(
            (a.first - b.first) * poseResult.imageWidth,
            (a.second - b.second) * poseResult.imageHeight
        )
}
