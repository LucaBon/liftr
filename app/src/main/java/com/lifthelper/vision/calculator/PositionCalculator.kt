package com.lifthelper.vision.calculator

import android.util.Log
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseKeypoint
import com.lifthelper.domain.model.PoseResult
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * Data class to hold Y-positions of different body parts (in pixels)
 */
data class BodyPartPositions(
    val hipsY: Float?,
    val shouldersY: Float?,
    val headY: Float?,
    val kneesY: Float?
)

/**
 * Result of pixels-per-meter calibration with quality indicator
 */
data class CalibrationResult(
    val pixelsPerMeter: Float,
    val quality: com.lifthelper.domain.model.CalibrationQuality
)

class PositionCalculator @Inject constructor() {

    /**
     * Get vertical positions (Y-coordinates) of body parts for squats
     * Averages multiple keypoints for each body part for stability
     *
     * @return BodyPartPositions with Y-coordinates in pixels, or null if none are visible
     */
    fun getBodyPartPositions(poseResult: PoseResult): BodyPartPositions? {
        val landmarks = poseResult.landmarks

        // Hips: average of left (23) and right (24)
        val leftHip = landmarks.getOrNull(23)
        val rightHip = landmarks.getOrNull(24)
        val hipsY = if (leftHip != null && rightHip != null &&
                       leftHip.visibility >= 0.5f && rightHip.visibility >= 0.5f) {
            (leftHip.y + rightHip.y) / 2f
        } else if (leftHip != null && leftHip.visibility >= 0.5f) {
            leftHip.y
        } else if (rightHip != null && rightHip.visibility >= 0.5f) {
            rightHip.y
        } else null

        // Shoulders: average of left (11) and right (12)
        val leftShoulder = landmarks.getOrNull(11)
        val rightShoulder = landmarks.getOrNull(12)
        val shouldersY = if (leftShoulder != null && rightShoulder != null &&
                            leftShoulder.visibility >= 0.5f && rightShoulder.visibility >= 0.5f) {
            (leftShoulder.y + rightShoulder.y) / 2f
        } else if (leftShoulder != null && leftShoulder.visibility >= 0.5f) {
            leftShoulder.y
        } else if (rightShoulder != null && rightShoulder.visibility >= 0.5f) {
            rightShoulder.y
        } else null

        // Head: average of nose (0), eyes (2, 5), ears (7, 8)
        val headKeypoints = listOfNotNull(
            landmarks.getOrNull(0),  // nose
            landmarks.getOrNull(2),  // left eye
            landmarks.getOrNull(5),  // right eye
            landmarks.getOrNull(7),  // left ear
            landmarks.getOrNull(8)   // right ear
        ).filter { it.visibility >= 0.5f }

        val headY = if (headKeypoints.isNotEmpty()) {
            headKeypoints.map { it.y }.average().toFloat()
        } else null

        // Knees: average of left (25) and right (26)
        val leftKnee = landmarks.getOrNull(25)
        val rightKnee = landmarks.getOrNull(26)
        val kneesY = if (leftKnee != null && rightKnee != null &&
                        leftKnee.visibility >= 0.5f && rightKnee.visibility >= 0.5f) {
            (leftKnee.y + rightKnee.y) / 2f
        } else if (leftKnee != null && leftKnee.visibility >= 0.5f) {
            leftKnee.y
        } else if (rightKnee != null && rightKnee.visibility >= 0.5f) {
            rightKnee.y
        } else null

        return if (hipsY != null || shouldersY != null || headY != null || kneesY != null) {
            BodyPartPositions(hipsY, shouldersY, headY, kneesY)
        } else null
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
    fun pixelsToMeters(
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
    fun getCalibrationQuality(
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
        ).filter { it.visibility >= 0.5f }

        // Get hip position (average of visible hip keypoints)
        val hipKeypoints = listOfNotNull(
            landmarks.getOrNull(23), // left hip
            landmarks.getOrNull(24)  // right hip
        ).filter { it.visibility >= 0.5f }

        // Get ankle position (average of visible ankle keypoints)
        val ankleKeypoints = listOfNotNull(
            landmarks.getOrNull(27), // left ankle
            landmarks.getOrNull(28)  // right ankle
        ).filter { it.visibility >= 0.5f }

        val hasHead = headKeypoints.isNotEmpty()
        val hasHips = hipKeypoints.isNotEmpty()
        val hasAnkles = ankleKeypoints.isNotEmpty()

        // Check if calibration data is available
        if (calibrationData == null) {
            Log.e(com.lifthelper.util.Constants.TAG_CALIBRATION,
                "❌ No calibration data available")
            return CalibrationResult(
                pixelsPerMeter = 1000f, // Default fallback
                quality = com.lifthelper.domain.model.CalibrationQuality.NONE
            )
        }

        // Strategy 1: Full body visible (head to ankles) - BEST
        if (hasHead && hasAnkles) {
            val headY = headKeypoints.map { it.y }.average().toFloat()
            val ankleY = ankleKeypoints.map { it.y }.average().toFloat()
            val detectedHeightNormalized = kotlin.math.abs(ankleY - headY)
            val detectedHeightPixels = detectedHeightNormalized * poseResult.imageHeight

            Log.d(com.lifthelper.util.Constants.TAG_CALIBRATION,
                "✅ Full body calibration: ${detectedHeightPixels.toInt()}px = ${calibrationData.userHeight}m")

            return CalibrationResult(
                pixelsPerMeter = detectedHeightPixels / calibrationData.userHeight,
                quality = com.lifthelper.domain.model.CalibrationQuality.OPTIMAL
            )
        }

        // Strategy 2: Upper body only (head to hips) - estimate full height
        if (hasHead && hasHips) {
            val headY = headKeypoints.map { it.y }.average().toFloat()
            val hipY = hipKeypoints.map { it.y }.average().toFloat()
            val torsoHeightNormalized = kotlin.math.abs(hipY - headY)
            val torsoHeightPixels = torsoHeightNormalized * poseResult.imageHeight

            // Anthropometric ratio: torso (head to hip) ≈ 52% of total height
            val estimatedFullHeightPixels = torsoHeightPixels / 0.52f

            Log.w(com.lifthelper.util.Constants.TAG_CALIBRATION,
                "⚠️ Partial body (torso only): estimated ${estimatedFullHeightPixels.toInt()}px = ${calibrationData.userHeight}m")

            return CalibrationResult(
                pixelsPerMeter = estimatedFullHeightPixels / calibrationData.userHeight,
                quality = com.lifthelper.domain.model.CalibrationQuality.ESTIMATED
            )
        }

        // Strategy 3: Lower body only (hips to ankles) - estimate full height
        if (hasHips && hasAnkles) {
            val hipY = hipKeypoints.map { it.y }.average().toFloat()
            val ankleY = ankleKeypoints.map { it.y }.average().toFloat()
            val legHeightNormalized = kotlin.math.abs(ankleY - hipY)
            val legHeightPixels = legHeightNormalized * poseResult.imageHeight

            // Anthropometric ratio: legs (hip to ankle) ≈ 48% of total height
            val estimatedFullHeightPixels = legHeightPixels / 0.48f

            Log.w(com.lifthelper.util.Constants.TAG_CALIBRATION,
                "⚠️ Partial body (legs only): estimated ${estimatedFullHeightPixels.toInt()}px = ${calibrationData.userHeight}m")

            return CalibrationResult(
                pixelsPerMeter = estimatedFullHeightPixels / calibrationData.userHeight,
                quality = com.lifthelper.domain.model.CalibrationQuality.ESTIMATED
            )
        }

        // Strategy 4: Fall back to stored calibration (no valid segments detected)
        Log.e(com.lifthelper.util.Constants.TAG_CALIBRATION,
            "❌ Cannot detect body segments, using stored calibration: ${calibrationData.pixelsPerMeter}px/m")

        return CalibrationResult(
            pixelsPerMeter = calibrationData.pixelsPerMeter,
            quality = com.lifthelper.domain.model.CalibrationQuality.FALLBACK
        )
    }

    private fun getMidpoint(p1: PoseKeypoint, p2: PoseKeypoint): Pair<Float, Float> {
        return Pair((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
    }

    private fun distance(p1: PoseKeypoint, p2: PoseKeypoint): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }
}
