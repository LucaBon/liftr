package com.lifthelper.domain.model

import com.lifthelper.domain.value.Meters
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Defines different squat depth variations based on biomechanics research and competitive standards.
 *
 * Research sources:
 * - Quarter/Half/Full definitions: Brookbush Institute, Sport Science Insider
 * - Powerlifting standards: IPF Technical Rules, PowerliftingToWin
 *
 * Depth is measured by the vertical distance relationship between hip and knee positions.
 *
 * Note: minHipKneeDistance is stored as a Float (not Meters) because it can be negative
 * (negative = hips above knees, positive = hips below knees)
 */
enum class SquatDepthType(
    val displayName: String,
    val description: String,
    val minHipKneeDistance: Float // Signed distance in meters
) {
    /**
     * Quarter Squat: ~135° knee flexion
     * Hips remain well above parallel, minimal depth
     * Best for: Explosive power, rate of force development, athletic training
     * Hip-knee distance: Hips 15cm+ above knees (min distance = -0.15)
     */
    QUARTER(
        displayName = "Quarter Squat",
        description = "Minimal depth - explosive power training",
        minHipKneeDistance = -0.15f // Hips 15cm+ above knees
    ),

    /**
     * Half Squat: ~90° knee flexion
     * Thighs roughly halfway to parallel
     * Best for: Athletic performance, jump training, moderate depth work
     * Hip-knee distance: Hips 8cm+ above knees (min distance = -0.08)
     */
    HALF(
        displayName = "Half Squat",
        description = "Moderate depth - athletic performance",
        minHipKneeDistance = -0.08f // Hips 8cm+ above knees
    ),

    /**
     * Powerlifting/Parallel Squat: IPF competition standard
     * Hip crease must drop at or below the top of the knee
     * Best for: Competition powerlifting, standardized depth
     * Hip-knee distance: Hips at or below knee level (min distance = -0.05)
     * Note: -0.05 allows 5cm above with default 2cm tolerance
     */
    POWERLIFTING(
        displayName = "Powerlifting (Parallel)",
        description = "Competition standard - hip crease below knee",
        minHipKneeDistance = -0.05f // Hips within 5cm of knee level
    ),

    /**
     * Full/ATG (Ass-to-Grass) Squat: ~140°+ knee flexion
     * Hip crease well below knee level, maximum depth
     * Best for: Maximum muscle development, Olympic weightlifting, mobility
     * Hip-knee distance: Hips 5cm+ below knees (min distance = +0.05)
     */
    FULL(
        displayName = "Full Depth (ATG)",
        description = "Maximum depth - muscle development & mobility",
        minHipKneeDistance = 0.05f // Hips 5cm+ below knees
    );

    /**
     * Checks if the current hip and knee positions meet this depth requirement.
     *
     * Logic differs based on depth type:
     * - QUARTER/HALF: Maximum depth constraints (don't go TOO deep)
     *   Example: QUARTER requires hips ≥15cm above knees (actualDist ≤ -0.15)
     * - POWERLIFTING/FULL: Minimum depth constraints (must go deep ENOUGH)
     *   Example: FULL requires hips ≥5cm below knees (actualDist >= +0.05)
     *
     * @param hipsY Y-coordinate of hip position (normalized, Y-axis pointing down)
     * @param kneesY Y-coordinate of knee position (normalized, Y-axis pointing down)
     * @param tolerance Additional tolerance in meters (default 0.02m = 2cm)
     * @return true if depth requirement is met
     */
    fun isDepthAchieved(
        hipsY: Float,
        kneesY: Float,
        tolerance: Meters = Meters(0.02f)
    ): Boolean {
        // In normalized coordinates with Y-axis pointing down:
        // - Higher Y values = lower in space
        // - Lower Y values = higher in space
        //
        // Distance: hipsY - kneesY
        // - Positive = hips below knees (deeper squat)
        // - Negative = hips above knees (shallower squat)
        val actualDistance = hipsY - kneesY

        return when (this) {
            QUARTER, HALF -> {
                // Maximum depth: don't go deeper than the limit
                // minHipKneeDistance is negative (e.g., -0.15 = 15cm above)
                // We want: actualDistance <= (minHipKneeDistance + tolerance)
                // E.g., -0.20 <= (-0.15 + 0.02) = -0.20 <= -0.13 ✓ PASS (20cm above is ok)
                // E.g., -0.10 <= (-0.15 + 0.02) = -0.10 <= -0.13 ✗ FAIL (10cm above is too deep)
                actualDistance <= (minHipKneeDistance + tolerance.value)
            }
            POWERLIFTING, FULL -> {
                // Minimum depth: must go at least this deep
                // Test: actualDistance >= (minHipKneeDistance - tolerance)
                // E.g., -0.03 >= (-0.05 - 0.02) = -0.03 >= -0.07 ✓ PASS (3cm above is ok for powerlifting)
                // E.g., +0.03 >= (+0.05 - 0.02) = +0.03 >= +0.03 ✓ PASS (3cm below is ok for full with tolerance)
                actualDistance >= (minHipKneeDistance - tolerance.value)
            }
        }
    }

    companion object {
        /**
         * Default depth type for new users
         */
        val DEFAULT = POWERLIFTING

        /**
         * Parse from string name (case-insensitive)
         */
        fun fromString(name: String): SquatDepthType {
            return entries.firstOrNull {
                it.name.equals(name, ignoreCase = true)
            } ?: DEFAULT
        }

        /**
         * Calculates the required femur angle for this depth type.
         * Femur angle is measured from vertical (0° = standing, 90° = horizontal/parallel)
         */
        fun SquatDepthType.getRequiredFemurAngle(): Float {
            return when (this) {
                QUARTER -> 45f      // Femur 45° from vertical (shallow)
                HALF -> 60f         // Femur 60° from vertical
                POWERLIFTING -> 85f // Femur near horizontal (parallel)
                FULL -> 95f         // Femur past horizontal (deep)
            }
        }
    }
}

/**
 * Result of depth validation with confidence scoring.
 *
 * @property isValid Whether the depth requirement was met
 * @property confidence Confidence score (0.0 to 1.0) based on multiple validation methods
 * @property methods List of validation methods used and their individual results
 * @property debugInfo Human-readable debug information
 */
data class DepthValidationResult(
    val isValid: Boolean,
    val confidence: Float,
    val methods: List<ValidationMethod>,
    val debugInfo: String = ""
) {
    /**
     * Individual validation method result
     */
    data class ValidationMethod(
        val name: String,
        val passed: Boolean,
        val weight: Float,
        val value: String = "" // e.g., "85.2°" for angle, "2.5cm" for distance
    )

    companion object {
        /**
         * Creates a simple validation result (for backwards compatibility)
         */
        fun simple(isValid: Boolean, debugInfo: String = ""): DepthValidationResult {
            return DepthValidationResult(
                isValid = isValid,
                confidence = if (isValid) 1.0f else 0.0f,
                methods = emptyList(),
                debugInfo = debugInfo
            )
        }
    }
}
