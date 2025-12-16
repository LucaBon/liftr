package com.lifthelper.domain.model

import kotlinx.serialization.Serializable

/**
 * Measurement issues detected during capture
 */
@Serializable
data class MeasurementIssue(
    val measurement: String,      // e.g., "Upper Arm", "Hip Width"
    val issue: String,            // e.g., "Elbow not visible", "Value out of range (3.2x height)"
    val usedFallback: Boolean     // Whether a fallback value was used
)

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
    val alignmentMessage: String? = null, // Optional message about camera alignment during capture
    val measurementIssues: List<MeasurementIssue> = emptyList()  // Issues detected during measurement
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
         * Calculate anthropometric data from pose landmarks (frontal view).
         *
         * This method delegates to AnthropometricDataBuilder for the extraction logic.
         * It is maintained for backward compatibility with existing code.
         *
         * @param poseResult Detected pose with landmarks
         * @param userHeight User's actual height in meters
         * @param userWeight User's weight in kg (optional)
         * @param manualShoulderWidth Manual shoulder width override (optional)
         * @param isFrontCamera Whether front camera is being used
         * @return AnthropometricData if successful, null if extraction fails
         */
        fun fromPoseResult(
            poseResult: PoseResult,
            userHeight: Float,
            userWeight: Float? = null,
            manualShoulderWidth: Float? = null,
            isFrontCamera: Boolean = false
        ): AnthropometricData? {
            // Create builder with required dependencies
            val builder = createBuilder()

            // Delegate to builder
            return builder.build(
                poseResult = poseResult,
                userHeight = userHeight,
                userWeight = userWeight,
                manualShoulderWidth = manualShoulderWidth,
                isFrontCamera = isFrontCamera
            ).getOrNull()
        }

        /**
         * Creates a builder instance with all required dependencies.
         * In production code, this should be injected via dependency injection.
         * This method exists for backward compatibility with the static factory pattern.
         */
        private fun createBuilder(): com.lifthelper.domain.builder.AnthropometricDataBuilder {
            // Create all dependencies
            val logger = com.lifthelper.infrastructure.logging.AndroidLogger()
            val poseValidator = com.lifthelper.domain.validation.PoseValidator()
            val coordinateCalculator = com.lifthelper.domain.calculator.CoordinateCalculator()
            val segmentExtractor = com.lifthelper.domain.extractor.BodySegmentExtractor(coordinateCalculator)
            val measurementValidator = com.lifthelper.domain.validation.MeasurementValidator()
            val alignmentChecker = com.lifthelper.vision.calculator.CameraAlignmentChecker()

            // Create and return builder
            return com.lifthelper.domain.builder.AnthropometricDataBuilder(
                poseValidator = poseValidator,
                coordinateCalculator = coordinateCalculator,
                segmentExtractor = segmentExtractor,
                measurementValidator = measurementValidator,
                alignmentChecker = alignmentChecker,
                logger = logger
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
