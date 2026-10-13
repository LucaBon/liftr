package com.liftr.domain.exercise

import com.liftr.domain.model.DepthValidationResult
import com.liftr.domain.model.SquatDepthType
import com.liftr.domain.value.Meters
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Utility class for robust squat depth validation using multiple methods.
 *
 * Implements three validation approaches:
 * 1. Hip crease estimation (primary for powerlifting)
 * 2. Femur angle calculation (good for all depth types)
 * 3. Hip joint vs knee (fallback, simple check)
 *
 * Uses a weighted voting system to provide confidence scoring.
 */
object SquatDepthValidator {

    private const val HIP_CREASE_WEIGHT = 0.5f  // 50% weight - most important for powerlifting
    private const val FEMUR_ANGLE_WEIGHT = 0.3f  // 30% weight - reliable across depths
    private const val SIMPLE_CHECK_WEIGHT = 0.2f // 20% weight - fallback method

    private const val CONFIDENCE_THRESHOLD = 0.6f // Need 60% confidence to pass

    /**
     * Performs robust depth validation using multiple methods with confidence scoring.
     *
     * @param state Current exercise phase state with position data
     * @param depthType Required squat depth type
     * @param tolerance Measurement tolerance in meters (default 2cm)
     * @return Validation result with confidence score and method breakdown
     */
    fun validateDepth(
        state: ExercisePhaseState,
        depthType: SquatDepthType,
        tolerance: Meters = Meters(0.02f)
    ): DepthValidationResult {
        val methods = mutableListOf<DepthValidationResult.ValidationMethod>()

        // Method 1: Hip crease estimation (primary for powerlifting)
        val hipCreaseResult = checkHipCreaseDepth(state, depthType, tolerance)
        if (hipCreaseResult != null) {
            methods.add(hipCreaseResult)
        }

        // Method 2: Femur angle (good for all depth types)
        val femurAngleResult = checkFemurAngle(state, depthType)
        if (femurAngleResult != null) {
            methods.add(femurAngleResult)
        }

        // Method 3: Simple hip joint vs knee (fallback)
        val simpleCheckResult = checkSimpleHipKnee(state, depthType, tolerance)
        if (simpleCheckResult != null) {
            methods.add(simpleCheckResult)
        }

        // No methods available - fail safe to avoid counting unverifiable reps (BUG-C fix)
        if (methods.isEmpty()) {
            return DepthValidationResult.simple(
                isValid = false,
                debugInfo = "No validation methods available (missing landmarks) - rejecting unverifiable depth"
            )
        }

        // Calculate weighted confidence
        val totalWeight = methods.sumOf { it.weight.toDouble() }.toFloat()
        val passedWeight = methods.filter { it.passed }.sumOf { it.weight.toDouble() }.toFloat()
        val confidence = passedWeight / totalWeight

        val isValid = confidence >= CONFIDENCE_THRESHOLD

        val debugInfo = buildString {
            append("Depth validation: ")
            append(if (isValid) "PASS" else "FAIL")
            append(" (confidence: ${(confidence * 100).toInt()}%)\n")
            methods.forEach { method ->
                append("  - ${method.name}: ")
                append(if (method.passed) "PASS" else "FAIL")
                append(" ${method.value}\n")
            }
        }

        return DepthValidationResult(
            isValid = isValid,
            confidence = confidence,
            methods = methods,
            debugInfo = debugInfo
        )
    }

    /**
     * Method 1: Estimates hip crease position and validates depth.
     * The hip crease is where the thigh meets the torso - this is what
     * judges look for in powerlifting, not the hip joint landmark.
     */
    private fun checkHipCreaseDepth(
        state: ExercisePhaseState,
        depthType: SquatDepthType,
        tolerance: Meters
    ): DepthValidationResult.ValidationMethod? {
        if (state.currentHipsY == null || state.currentKneesY == null) {
            return null
        }

        // Estimate hip crease position
        val hipCreaseY = estimateHipCrease(state) ?: return null
        val kneeY = state.currentKneesY!!

        // Distance from hip crease to knee (positive = crease below knee)
        val creaseToKneeDistance = hipCreaseY - kneeY

        // Check if depth is achieved based on type
        val passed = when (depthType) {
            SquatDepthType.QUARTER -> creaseToKneeDistance <= -0.15f + tolerance.value
            SquatDepthType.HALF -> creaseToKneeDistance <= -0.08f + tolerance.value
            SquatDepthType.POWERLIFTING -> creaseToKneeDistance >= -tolerance.value // At or below
            SquatDepthType.FULL -> creaseToKneeDistance >= 0.05f - tolerance.value
        }

        return DepthValidationResult.ValidationMethod(
            name = "Hip Crease",
            passed = passed,
            weight = HIP_CREASE_WEIGHT,
            value = "${(abs(creaseToKneeDistance) * 100).toInt()}cm ${if (creaseToKneeDistance >= 0) "below" else "above"}"
        )
    }

    /**
     * Estimates the hip crease position from hip joint and knee positions.
     * The crease rises 10-15% of hip-to-knee distance above the hip joint landmark.
     */
    private fun estimateHipCrease(state: ExercisePhaseState): Float? {
        val hipsY = state.currentHipsY ?: return null
        val kneesY = state.currentKneesY ?: return null

        val hipToKneeDistance = abs(kneesY - hipsY)

        // Hip crease is typically 12% of hip-to-knee distance above hip joint
        val creaseOffset = hipToKneeDistance * 0.12f

        // Hip crease is ABOVE (lower Y value in our coordinate system) the hip joint
        return hipsY - creaseOffset
    }

    /**
     * Method 2: Calculates femur angle and validates depth.
     * Femur angle is measured from vertical (0° = standing, 90° = parallel).
     */
    private fun checkFemurAngle(
        state: ExercisePhaseState,
        depthType: SquatDepthType
    ): DepthValidationResult.ValidationMethod? {
        val angle = calculateFemurAngle(state) ?: return null

        val requiredAngle = when (depthType) {
            SquatDepthType.QUARTER -> 45f
            SquatDepthType.HALF -> 60f
            SquatDepthType.POWERLIFTING -> 85f
            SquatDepthType.FULL -> 95f
        }

        val passed = angle >= requiredAngle

        return DepthValidationResult.ValidationMethod(
            name = "Femur Angle",
            passed = passed,
            weight = FEMUR_ANGLE_WEIGHT,
            value = "${angle.toInt()}° (need ${requiredAngle.toInt()}°)"
        )
    }

    /**
     * Calculates the femur angle from vertical using hip and knee positions.
     * Returns angle in degrees (0° = standing, 90° = horizontal/parallel).
     */
    private fun calculateFemurAngle(state: ExercisePhaseState): Float? {
        val hipsY = state.currentHipsY ?: return null
        val kneesY = state.currentKneesY ?: return null
        val hipsX = state.currentHipsX ?: return null
        val kneesX = state.currentKneesX ?: return null

        val dx = abs(hipsX - kneesX)
        val dy = abs(hipsY - kneesY)

        // Angle from vertical (atan2 gives angle from horizontal, so we adjust)
        val angleRad = atan2(dx, dy)
        return Math.toDegrees(angleRad.toDouble()).toFloat()
    }

    /**
     * Method 3: Simple hip joint vs knee comparison (fallback method).
     * Uses the original simple depth check as a backup validation.
     */
    private fun checkSimpleHipKnee(
        state: ExercisePhaseState,
        depthType: SquatDepthType,
        tolerance: Meters
    ): DepthValidationResult.ValidationMethod? {
        val hipsY = state.currentHipsY ?: return null
        val kneesY = state.currentKneesY ?: return null

        val passed = depthType.isDepthAchieved(
            hipsY = hipsY,
            kneesY = kneesY,
            tolerance = tolerance
        )

        val distance = hipsY - kneesY
        return DepthValidationResult.ValidationMethod(
            name = "Hip-Knee Simple",
            passed = passed,
            weight = SIMPLE_CHECK_WEIGHT,
            value = "${(abs(distance) * 100).toInt()}cm ${if (distance >= 0) "below" else "above"}"
        )
    }
}
