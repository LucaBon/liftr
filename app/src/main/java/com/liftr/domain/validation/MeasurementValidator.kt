package com.liftr.domain.validation

import com.liftr.domain.model.MeasurementIssue

/**
 * Validates anthropometric measurements for realistic values.
 *
 * This class performs sanity checks on body measurements to ensure
 * they fall within realistic human proportions. Out-of-range values
 * are flagged and fallback values are provided.
 */
class MeasurementValidator {

    /**
     * Validates hip width measurement.
     *
     * Expected range: 5-25% of height (15-45cm for 1.8m person)
     *
     * @param width Hip width in normalized units
     * @param totalHeight Total body height in normalized units
     * @param userHeight User's actual height in meters
     * @return MeasurementValidationResult with validated value
     */
    fun validateHipWidth(
        width: Float,
        totalHeight: Float,
        userHeight: Float
    ): MeasurementValidationResult {
        val ratio = width / totalHeight

        return if (ratio in HIP_WIDTH_MIN_RATIO..HIP_WIDTH_MAX_RATIO) {
            MeasurementValidationResult(
                isValid = true,
                validatedValue = width,
                issue = null
            )
        } else {
            val actualCm = (ratio * userHeight * 100).toInt()
            MeasurementValidationResult(
                isValid = false,
                validatedValue = STANDARD_HIP_WIDTH_RATIO * totalHeight,
                issue = MeasurementIssue(
                    measurement = "Hip Width",
                    issue = "Detected value unrealistic (${actualCm}cm, expected 15-45cm). Using standard ratio.",
                    usedFallback = true
                )
            )
        }
    }

    /**
     * Validates knee width measurement.
     *
     * Expected range: 3-20% of height (5-35cm for 1.8m person)
     *
     * @param width Knee width in normalized units
     * @param totalHeight Total body height in normalized units
     * @param userHeight User's actual height in meters
     * @return MeasurementValidationResult with validated value
     */
    fun validateKneeWidth(
        width: Float,
        totalHeight: Float,
        userHeight: Float
    ): MeasurementValidationResult {
        val ratio = width / totalHeight

        return if (ratio in KNEE_WIDTH_MIN_RATIO..KNEE_WIDTH_MAX_RATIO) {
            MeasurementValidationResult(
                isValid = true,
                validatedValue = width,
                issue = null
            )
        } else {
            val actualCm = (ratio * userHeight * 100).toInt()
            MeasurementValidationResult(
                isValid = false,
                validatedValue = STANDARD_KNEE_WIDTH_RATIO * totalHeight,
                issue = MeasurementIssue(
                    measurement = "Knee Width",
                    issue = "Detected value unrealistic (${actualCm}cm, expected 5-35cm). Using standard ratio.",
                    usedFallback = true
                )
            )
        }
    }

    /**
     * Validates upper arm length measurement.
     *
     * Expected range: 10-35% of height (17-60cm for 1.8m person)
     *
     * @param length Upper arm length in normalized units
     * @param totalHeight Total body height in normalized units
     * @param userHeight User's actual height in meters
     * @return MeasurementValidationResult with validated value
     */
    fun validateUpperArmLength(
        length: Float,
        totalHeight: Float,
        userHeight: Float
    ): MeasurementValidationResult {
        val ratio = length / totalHeight

        return if (ratio in UPPER_ARM_MIN_RATIO..UPPER_ARM_MAX_RATIO) {
            MeasurementValidationResult(
                isValid = true,
                validatedValue = length,
                issue = null
            )
        } else {
            val actualCm = (ratio * userHeight * 100).toInt()
            MeasurementValidationResult(
                isValid = false,
                validatedValue = STANDARD_UPPER_ARM_RATIO * totalHeight,
                issue = MeasurementIssue(
                    measurement = "Upper Arm",
                    issue = "Detected value unrealistic (${actualCm}cm, expected 17-60cm). Using standard ratio.",
                    usedFallback = true
                )
            )
        }
    }

    /**
     * Validates forearm length measurement.
     *
     * Expected range: 10-30% of height (17-52cm for 1.8m person)
     *
     * @param length Forearm length in normalized units
     * @param totalHeight Total body height in normalized units
     * @param userHeight User's actual height in meters
     * @return MeasurementValidationResult with validated value
     */
    fun validateForearmLength(
        length: Float,
        totalHeight: Float,
        userHeight: Float
    ): MeasurementValidationResult {
        val ratio = length / totalHeight

        return if (ratio in FOREARM_MIN_RATIO..FOREARM_MAX_RATIO) {
            MeasurementValidationResult(
                isValid = true,
                validatedValue = length,
                issue = null
            )
        } else {
            val actualCm = (ratio * userHeight * 100).toInt()
            MeasurementValidationResult(
                isValid = false,
                validatedValue = STANDARD_FOREARM_RATIO * totalHeight,
                issue = MeasurementIssue(
                    measurement = "Forearm",
                    issue = "Detected value unrealistic (${actualCm}cm, expected 17-52cm). Using standard ratio.",
                    usedFallback = true
                )
            )
        }
    }

    /**
     * Validates shoulder width measurement.
     *
     * Expected range: 10-40% of height
     *
     * @param width Shoulder width in normalized units
     * @param totalHeight Total body height in normalized units
     * @return true if shoulder width is within realistic range
     */
    fun validateShoulderWidthRange(width: Float, totalHeight: Float): Boolean {
        val ratio = width / totalHeight
        return ratio in SHOULDER_WIDTH_MIN_RATIO..SHOULDER_WIDTH_MAX_RATIO
    }

    /**
     * Validates torso height measurement.
     *
     * Expected range: 15-45% of total height
     *
     * @param torsoHeight Torso height in normalized units
     * @param totalHeight Total body height in normalized units
     * @return true if torso height is within realistic range
     */
    fun validateTorsoHeightRange(torsoHeight: Float, totalHeight: Float): Boolean {
        val ratio = torsoHeight / totalHeight
        return ratio in TORSO_HEIGHT_MIN_RATIO..TORSO_HEIGHT_MAX_RATIO
    }

    /**
     * Assesses how much of the camera frame the body occupies.
     *
     * @param totalHeightNorm Total body height in normalized coordinates (0-1)
     * @param imageHeight Image height in pixels
     * @return FrameSizeResult with quality assessment
     */
    fun validateBodySizeInFrame(
        totalHeightNorm: Float,
        imageHeight: Int
    ): FrameSizeResult {
        val bodyHeightPixels = (totalHeightNorm * imageHeight).toInt()
        val bodyHeightPercent = (totalHeightNorm * 100).toInt()

        val quality = when {
            totalHeightNorm < 0.20f -> FrameSizeQuality.TOO_SMALL
            totalHeightNorm > 0.50f -> FrameSizeQuality.OPTIMAL
            else -> FrameSizeQuality.ACCEPTABLE
        }

        return FrameSizeResult(
            percentage = bodyHeightPercent,
            pixels = bodyHeightPixels,
            quality = quality
        )
    }

    /**
     * Validates that segment heights are positive and reasonable.
     *
     * @param headNeckHeight Head+neck height
     * @param torsoHeight Torso height
     * @param upperLegHeight Upper leg height
     * @param lowerLegHeight Lower leg height
     * @param totalHeight Total height for reference
     * @return true if all segments are valid
     */
    fun validateSegmentHeights(
        headNeckHeight: Float,
        torsoHeight: Float,
        upperLegHeight: Float,
        lowerLegHeight: Float,
        totalHeight: Float
    ): Boolean {
        // All segments must be positive
        if (headNeckHeight <= 0 || torsoHeight <= 0 || upperLegHeight <= 0) {
            return false
        }

        // Lower leg can be estimated, but should be reasonable
        if (lowerLegHeight <= 0 || lowerLegHeight > totalHeight * 0.5f) {
            return false
        }

        return true
    }

    companion object {
        // Hip width validation ranges (as ratio of total height)
        private const val HIP_WIDTH_MIN_RATIO = 0.05f
        private const val HIP_WIDTH_MAX_RATIO = 0.25f
        private const val STANDARD_HIP_WIDTH_RATIO = 0.19f

        // Knee width validation ranges
        private const val KNEE_WIDTH_MIN_RATIO = 0.03f
        private const val KNEE_WIDTH_MAX_RATIO = 0.20f
        private const val STANDARD_KNEE_WIDTH_RATIO = 0.10f

        // Upper arm validation ranges
        private const val UPPER_ARM_MIN_RATIO = 0.10f
        private const val UPPER_ARM_MAX_RATIO = 0.35f
        private const val STANDARD_UPPER_ARM_RATIO = 0.19f

        // Forearm validation ranges
        private const val FOREARM_MIN_RATIO = 0.10f
        private const val FOREARM_MAX_RATIO = 0.30f
        private const val STANDARD_FOREARM_RATIO = 0.16f

        // Shoulder width validation ranges
        private const val SHOULDER_WIDTH_MIN_RATIO = 0.10f
        private const val SHOULDER_WIDTH_MAX_RATIO = 0.40f

        // Torso height validation ranges
        private const val TORSO_HEIGHT_MIN_RATIO = 0.15f
        private const val TORSO_HEIGHT_MAX_RATIO = 0.45f
    }
}

/**
 * Result of measurement validation.
 *
 * @property isValid Whether the measurement is within realistic bounds
 * @property validatedValue The validated value (original if valid, fallback if invalid)
 * @property issue Measurement issue if validation failed, null otherwise
 */
data class MeasurementValidationResult(
    val isValid: Boolean,
    val validatedValue: Float,
    val issue: MeasurementIssue?
)

/**
 * Result of body size in frame assessment.
 *
 * @property percentage Body height as percentage of frame height
 * @property pixels Body height in pixels
 * @property quality Quality assessment of body size in frame
 */
data class FrameSizeResult(
    val percentage: Int,
    val pixels: Int,
    val quality: FrameSizeQuality
)

/**
 * Quality assessment for body size in camera frame.
 */
enum class FrameSizeQuality {
    /**
     * Body occupies less than 20% of frame - user should move closer
     */
    TOO_SMALL,

    /**
     * Body occupies 20-50% of frame - acceptable
     */
    ACCEPTABLE,

    /**
     * Body occupies more than 50% of frame - optimal
     */
    OPTIMAL
}
