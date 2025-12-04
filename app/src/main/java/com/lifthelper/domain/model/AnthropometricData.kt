package com.lifthelper.domain.model

import kotlinx.serialization.Serializable

/**
 * Comprehensive anthropometric measurements for personalized biomechanics calculations
 *
 * All measurements stored as ratios relative to total height for scale independence
 * Actual metric values calculated on-demand using user height
 */
@Serializable
data class AnthropometricData(
    // Body segment lengths (as ratio of total height)
    val headHeightRatio: Float,           // Head top to neck base
    val neckHeightRatio: Float,           // Neck base to shoulders
    val torsoHeightRatio: Float,          // Shoulders to hips
    val upperLegHeightRatio: Float,       // Hips to knees
    val lowerLegHeightRatio: Float,       // Knees to ankles
    val footHeightRatio: Float,           // Ankles to ground

    // Body segment widths (as ratio of total height)
    val shoulderWidthRatio: Float,        // Between shoulder joints
    val hipWidthRatio: Float,             // Between hip joints
    val kneeWidthRatio: Float,            // Between knees (standing width)

    // Limb lengths (as ratio of total height)
    val upperArmLengthRatio: Float,       // Shoulder to elbow
    val forearmLengthRatio: Float,        // Elbow to wrist
    val thighLengthRatio: Float,          // Hip to knee (same as upperLegHeightRatio)
    val shinLengthRatio: Float,           // Knee to ankle (same as lowerLegHeightRatio)

    // Body mass distribution (optional, for advanced biomechanics)
    val userWeight: Float? = null,        // Total body weight in kg

    // Metadata
    val captureTimestamp: Long = System.currentTimeMillis(),
    val captureQuality: CalibrationQuality = CalibrationQuality.OPTIMAL,
    val userHeight: Float,                // Total height in meters (reference for ratios)
    val alignmentMessage: String? = null  // Optional message about camera alignment during capture
) {
    companion object {
        /**
         * Standard anthropometric ratios based on biomechanics research
         * Used as fallback when camera measurement fails
         * Based on average adult proportions
         */
        fun getDefaultRatios(userHeight: Float, userWeight: Float? = null): AnthropometricData {
            return AnthropometricData(
                // Segment heights (sum = ~1.0)
                headHeightRatio = 0.13f,      // ~13% head + neck
                neckHeightRatio = 0.05f,      // ~5% neck
                torsoHeightRatio = 0.30f,     // ~30% torso
                upperLegHeightRatio = 0.25f,  // ~25% thigh
                lowerLegHeightRatio = 0.23f,  // ~23% shin
                footHeightRatio = 0.04f,      // ~4% foot height

                // Segment widths
                shoulderWidthRatio = 0.26f,   // ~26% shoulder width
                hipWidthRatio = 0.19f,        // ~19% hip width
                kneeWidthRatio = 0.10f,       // ~10% knee spacing

                // Limb lengths
                upperArmLengthRatio = 0.19f,  // ~19% upper arm
                forearmLengthRatio = 0.16f,   // ~16% forearm
                thighLengthRatio = 0.25f,     // Same as upper leg
                shinLengthRatio = 0.23f,      // Same as lower leg

                userWeight = userWeight,
                userHeight = userHeight,
                captureQuality = CalibrationQuality.FALLBACK
            )
        }

        /**
         * Calculate anthropometric data from pose landmarks (frontal view)
         */
        fun fromPoseResult(
            poseResult: PoseResult,
            userHeight: Float,
            userWeight: Float? = null,
            manualShoulderWidth: Float? = null,  // If provided by user, use this instead of estimating
            isFrontCamera: Boolean = false  // Front camera requires coordinate mirroring (doesn't affect distance calculations)
        ): AnthropometricData? {
            val landmarks = poseResult.landmarks
            val imageWidth = poseResult.imageWidth
            val imageHeight = poseResult.imageHeight
            val aspectRatio = imageWidth.toFloat() / imageHeight.toFloat()

            // Check if we have critical landmarks visible (ankles are optional for solo front camera mode)
            val criticalLandmarks = listOf(
                0,   // nose (head top proxy)
                7, 8, // ears
                11, 12, // shoulders
                23, 24, // hips
                25, 26  // knees
            )

            val criticalVisible = criticalLandmarks.all { idx ->
                landmarks.getOrNull(idx)?.visibility?.let { it >= 0.5f } ?: false
            }

            if (!criticalVisible) {
                return null  // Must have at least head to knees visible
            }

            // Check if ankles are visible (optional)
            val anklesVisible = listOf(27, 28).all { idx ->
                landmarks.getOrNull(idx)?.visibility?.let { it >= 0.5f } ?: false
            }

            // Helper to get average Y position
            fun avgY(vararg indices: Int): Float {
                return indices.toList()
                    .mapNotNull { landmarks.getOrNull(it) }
                    .filter { it.visibility >= 0.5f }
                    .map { it.y }
                    .average()
                    .toFloat()
            }

            // Helper to get average X position
            fun avgX(vararg indices: Int): Float {
                return indices.toList()
                    .mapNotNull { landmarks.getOrNull(it) }
                    .filter { it.visibility >= 0.5f }
                    .map { it.x }
                    .average()
                    .toFloat()
            }

            // Helper to calculate Euclidean distance with aspect ratio correction
            // X coordinates are scaled by aspect ratio to make them comparable to Y
            fun distance(x1: Float, y1: Float, x2: Float, y2: Float, aspectRatio: Float): Float {
                val dx = (x2 - x1) / aspectRatio // Scale X to match Y physical units
                val dy = y2 - y1
                return kotlin.math.sqrt(dx * dx + dy * dy)
            }

            // Calculate key positions
            // Note: Y-axis goes from 0 (top) to 1 (bottom) in normalized coordinates
            val headTopY = avgY(0, 7, 8)  // nose and ears average
            val shouldersY = avgY(11, 12)
            val hipsY = avgY(23, 24)
            val kneesY = avgY(25, 26)
            val anklesY = if (anklesVisible) avgY(27, 28) else null

            val shouldersX = (landmarks[11].x + landmarks[12].x) / 2f
            val hipsX = (landmarks[23].x + landmarks[24].x) / 2f

            // Total height in normalized coordinates
            // If ankles not visible, estimate total height from knees (knees are typically ~60% down from head)
            val totalHeightNorm = if (anklesY != null) {
                anklesY - headTopY  // Full body visible
            } else {
                // Estimate: if knees are at 60% of height, total = (kneesY - headTopY) / 0.6
                (kneesY - headTopY) / 0.6f
            }

            if (totalHeightNorm <= 0) {
                // Invalid: head should be above ankles
                return null
            }

            // Calculate segment heights (each going downward, so lower Y - upper Y)
            val headNeckHeight = shouldersY - headTopY  // Should be positive
            val torsoHeight = hipsY - shouldersY        // Should be positive
            val upperLegHeight = kneesY - hipsY         // Should be positive
            val lowerLegHeight = if (anklesY != null) {
                anklesY - kneesY  // Measured from actual ankles
            } else {
                // Ankles not visible (solo front camera mode with partial body in frame)
                // Estimate lower leg as ~25% of total height (typical proportion)
                totalHeightNorm * 0.25f
            }

            // Validate critical segments are positive
            if (headNeckHeight <= 0 || torsoHeight <= 0 || upperLegHeight <= 0) {
                return null  // Invalid pose data
            }

            // Lower leg can be estimated, so just check it's reasonable (not negative or too large)
            if (lowerLegHeight <= 0 || lowerLegHeight > totalHeightNorm * 0.5f) {
                return null  // Invalid estimation
            }

            // Calculate widths as horizontal distances
            // For frontal view, widths should be primarily along X-axis
            // We scale X by aspect ratio to get comparable units with Y
            val shoulderWidthX = kotlin.math.abs(landmarks[12].x - landmarks[11].x)
            val hipWidthX = kotlin.math.abs(landmarks[24].x - landmarks[23].x)
            val kneeWidthX = kotlin.math.abs(landmarks[26].x - landmarks[25].x)

            // Convert X widths to equivalent Y units by dividing by aspect ratio
            // This makes them comparable to vertical (Y) distances
            val shoulderWidthEstimated = shoulderWidthX / aspectRatio
            val hipWidth = hipWidthX / aspectRatio
            val kneeWidth = kneeWidthX / aspectRatio

            // Use manual shoulder width if provided, otherwise use estimated
            val shoulderWidth = if (manualShoulderWidth != null) {
                // Convert manual shoulder width (in meters) to normalized units
                // by dividing by user height and multiplying by total height in normalized coords
                (manualShoulderWidth / userHeight) * totalHeightNorm
            } else {
                shoulderWidthEstimated
            }

            // Calculate arm segments if visible (with aspect ratio correction)
            val upperArmLength = if (landmarks.getOrNull(13)?.visibility ?: 0f >= 0.5f) {
                distance(
                    landmarks[11].x, landmarks[11].y,
                    landmarks[13].x, landmarks[13].y,
                    aspectRatio
                )
            } else {
                0.19f * totalHeightNorm // fallback to standard ratio
            }

            val forearmLength = if (landmarks.getOrNull(13)?.visibility ?: 0f >= 0.5f &&
                                    landmarks.getOrNull(15)?.visibility ?: 0f >= 0.5f) {
                distance(
                    landmarks[13].x, landmarks[13].y,
                    landmarks[15].x, landmarks[15].y,
                    aspectRatio
                )
            } else {
                0.16f * totalHeightNorm // fallback to standard ratio
            }

            // Check camera alignment
            val alignmentChecker = com.lifthelper.vision.calculator.CameraAlignmentChecker()
            val alignmentResult = alignmentChecker.checkAlignment(poseResult, aspectRatio)

            // Determine capture quality based on body visibility and alignment
            val quality = when {
                // Poor alignment overrides other quality considerations
                alignmentResult.issue == com.lifthelper.vision.calculator.AlignmentIssue.TILTED_AND_SIDE ->
                    CalibrationQuality.FALLBACK
                alignmentResult.issue == com.lifthelper.vision.calculator.AlignmentIssue.SIDE_VIEW ->
                    CalibrationQuality.FALLBACK
                alignmentResult.issue == com.lifthelper.vision.calculator.AlignmentIssue.TILTED ->
                    CalibrationQuality.ESTIMATED  // Tilt can be tolerated better than side view
                // Good alignment - assess based on body visibility
                anklesVisible && totalHeightNorm > 0.5f -> CalibrationQuality.OPTIMAL  // Full body visible
                anklesVisible -> CalibrationQuality.ESTIMATED  // Full body but too close/far
                else -> CalibrationQuality.ESTIMATED  // Ankles estimated from knees
            }

            // Calculate ratios
            val shoulderWidthRatio = shoulderWidth / totalHeightNorm
            val torsoHeightRatio = torsoHeight / totalHeightNorm

            // Sanity check: shoulder width should be reasonable (10-40% of height)
            // Skip this check if using manual shoulder width (user input is trusted)
            if (manualShoulderWidth == null && (shoulderWidthRatio < 0.10f || shoulderWidthRatio > 0.40f)) {
                return null  // Likely bad pose detection or extreme angle
            }

            // Sanity check: torso should be 20-40% of height
            if (torsoHeightRatio < 0.15f || torsoHeightRatio > 0.45f) {
                return null  // Unrealistic proportions
            }

            return AnthropometricData(
                // Heights as ratios
                headHeightRatio = (headNeckHeight * 0.7f) / totalHeightNorm, // 70% for head, 30% for neck
                neckHeightRatio = (headNeckHeight * 0.3f) / totalHeightNorm,
                torsoHeightRatio = torsoHeight / totalHeightNorm,
                upperLegHeightRatio = upperLegHeight / totalHeightNorm,
                lowerLegHeightRatio = lowerLegHeight / totalHeightNorm,
                footHeightRatio = 0.04f, // Estimated (feet not reliably detected)

                // Widths as ratios
                shoulderWidthRatio = shoulderWidth / totalHeightNorm,
                hipWidthRatio = hipWidth / totalHeightNorm,
                kneeWidthRatio = kneeWidth / totalHeightNorm,

                // Limb lengths as ratios
                upperArmLengthRatio = upperArmLength / totalHeightNorm,
                forearmLengthRatio = forearmLength / totalHeightNorm,
                thighLengthRatio = upperLegHeight / totalHeightNorm,
                shinLengthRatio = lowerLegHeight / totalHeightNorm,

                userWeight = userWeight,
                userHeight = userHeight,
                captureTimestamp = System.currentTimeMillis(),
                captureQuality = quality,
                alignmentMessage = if (alignmentResult.issue != com.lifthelper.vision.calculator.AlignmentIssue.NONE) {
                    alignmentResult.message
                } else null
            )
        }
    }

    // Convenience methods to get actual measurements in meters
    fun getHeadHeight(): Float = headHeightRatio * userHeight
    fun getNeckHeight(): Float = neckHeightRatio * userHeight
    fun getTorsoHeight(): Float = torsoHeightRatio * userHeight
    fun getUpperLegHeight(): Float = upperLegHeightRatio * userHeight
    fun getLowerLegHeight(): Float = lowerLegHeightRatio * userHeight
    fun getFootHeight(): Float = footHeightRatio * userHeight

    fun getShoulderWidth(): Float = shoulderWidthRatio * userHeight
    fun getHipWidth(): Float = hipWidthRatio * userHeight
    fun getKneeWidth(): Float = kneeWidthRatio * userHeight

    fun getUpperArmLength(): Float = upperArmLengthRatio * userHeight
    fun getForearmLength(): Float = forearmLengthRatio * userHeight
    fun getThighLength(): Float = thighLengthRatio * userHeight
    fun getShinLength(): Float = shinLengthRatio * userHeight

    /**
     * Get segment mass as percentage of total body weight
     * Based on Dempster's regression equations and Clauser et al. (1969)
     */
    fun getSegmentMass(segment: BodySegment): Float? {
        return userWeight?.let { weight ->
            weight * when (segment) {
                BodySegment.HEAD -> 0.081f           // 8.1%
                BodySegment.TRUNK -> 0.497f          // 49.7%
                BodySegment.UPPER_ARM -> 0.028f      // 2.8% per arm
                BodySegment.FOREARM -> 0.016f        // 1.6% per arm
                BodySegment.HAND -> 0.006f           // 0.6% per hand
                BodySegment.THIGH -> 0.100f          // 10.0% per thigh
                BodySegment.SHANK -> 0.0465f         // 4.65% per shank
                BodySegment.FOOT -> 0.0145f          // 1.45% per foot
            }
        }
    }

    /**
     * Get center of mass location for a segment (as ratio from proximal end)
     * Based on Winter (2009) biomechanics data
     */
    fun getSegmentCOMRatio(segment: BodySegment): Float {
        return when (segment) {
            BodySegment.HEAD -> 0.50f
            BodySegment.TRUNK -> 0.43f
            BodySegment.UPPER_ARM -> 0.436f
            BodySegment.FOREARM -> 0.430f
            BodySegment.HAND -> 0.506f
            BodySegment.THIGH -> 0.433f
            BodySegment.SHANK -> 0.433f
            BodySegment.FOOT -> 0.50f
        }
    }

    /**
     * Validate that ratios sum to approximately 1.0 (within tolerance)
     */
    fun isValid(): Boolean {
        val totalHeightRatio = headHeightRatio + neckHeightRatio + torsoHeightRatio +
                              upperLegHeightRatio + lowerLegHeightRatio + footHeightRatio
        return totalHeightRatio in 0.9f..1.1f // 10% tolerance
    }
}

/**
 * Body segments for biomechanical calculations
 */
@Serializable
enum class BodySegment {
    HEAD,
    TRUNK,      // Torso
    UPPER_ARM,  // Shoulder to elbow
    FOREARM,    // Elbow to wrist
    HAND,
    THIGH,      // Hip to knee
    SHANK,      // Knee to ankle (shin)
    FOOT
}
