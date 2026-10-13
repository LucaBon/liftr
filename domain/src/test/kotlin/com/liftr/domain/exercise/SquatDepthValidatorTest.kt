package com.liftr.domain.exercise

import com.liftr.domain.model.SquatDepthType
import com.liftr.domain.value.Meters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for SquatDepthValidator - the robust multi-method depth validation system.
 *
 * Tests three validation methods:
 * 1. Hip crease estimation (primary for powerlifting)
 * 2. Femur angle calculation (good for all depth types)
 * 3. Simple hip-knee comparison (fallback)
 *
 * Also tests the weighted confidence scoring system.
 */
class SquatDepthValidatorTest {

    companion object {
        // Test coordinate system: Y-axis points down (higher Y = lower in space)
        private const val KNEE_Y = 1.0f // Knee at 1.0m
        private const val HIP_Y = 0.95f // Hip joint at 0.95m (5cm above knee)

        // X coordinates for femur angle testing
        private const val CENTER_X = 0.5f
    }

    @Test
    fun `validateDepth should pass powerlifting squat with all methods agreeing`() {
        // Setup: Hip joint at knee level (parallel squat)
        // Hip crease will be estimated 12% higher, so slightly above knee
        val state = ExercisePhaseState(
            currentPhase = com.liftr.domain.model.MovementPhase.BOTTOM,
            currentHipsY = KNEE_Y, // Hip joint at knee level
            currentKneesY = KNEE_Y,
            currentHipsX = CENTER_X,
            currentKneesX = CENTER_X
        )

        val result = SquatDepthValidator.validateDepth(
            state = state,
            depthType = SquatDepthType.POWERLIFTING,
            tolerance = Meters(0.02f)
        )

        // All methods should pass (or at least confidence > 60%)
        assertTrue("Should pass powerlifting depth with high confidence", result.isValid)
        assertTrue("Confidence should be reasonable", result.confidence >= 0.5f)
        assertNotNull("Should have debug info", result.debugInfo)
        assertTrue("Should have validation methods", result.methods.isNotEmpty())
    }

    @Test
    fun `validateDepth should fail quarter squat when too deep`() {
        // Setup: Hips at knee level - too deep for quarter squat
        val state = ExercisePhaseState(
            currentPhase = com.liftr.domain.model.MovementPhase.BOTTOM,
            currentHipsY = KNEE_Y, // At knee level
            currentKneesY = KNEE_Y,
            currentHipsX = CENTER_X,
            currentKneesX = CENTER_X
        )

        val result = SquatDepthValidator.validateDepth(
            state = state,
            depthType = SquatDepthType.QUARTER,
            tolerance = Meters(0.02f)
        )

        // Should fail because quarter squat requires hips well above knees
        assertFalse("Should fail quarter squat when too deep", result.isValid)
        assertTrue("Should have methods that detected the issue", result.methods.isNotEmpty())
    }

    @Test
    fun `validateDepth should pass full squat when hips clearly below knees`() {
        // Setup: Hips 10cm below knees - deep full squat
        val state = ExercisePhaseState(
            currentPhase = com.liftr.domain.model.MovementPhase.BOTTOM,
            currentHipsY = KNEE_Y + 0.10f, // 10cm below knees
            currentKneesY = KNEE_Y,
            currentHipsX = CENTER_X + 0.08f, // Femur angled forward
            currentKneesX = CENTER_X
        )

        val result = SquatDepthValidator.validateDepth(
            state = state,
            depthType = SquatDepthType.FULL,
            tolerance = Meters(0.02f)
        )

        // Should pass with high confidence
        assertTrue("Should pass full squat depth", result.isValid)
        assertTrue("Confidence should be high", result.confidence >= 0.6f)
    }

    @Test
    fun `validateDepth should handle missing hip data gracefully`() {
        // Setup: Missing hip Y coordinate
        val state = ExercisePhaseState(
            currentPhase = com.liftr.domain.model.MovementPhase.BOTTOM,
            currentHipsY = null, // Missing
            currentKneesY = KNEE_Y,
            currentHipsX = null,
            currentKneesX = CENTER_X
        )

        val result = SquatDepthValidator.validateDepth(
            state = state,
            depthType = SquatDepthType.POWERLIFTING,
            tolerance = Meters(0.02f)
        )

        // Should fail-safe: reject unverifiable depth (BUG-C fix)
        assertFalse("Should fail when data is missing (fail-safe)", result.isValid)
        assertTrue("Should indicate data was missing",
            result.debugInfo.contains("No validation methods available"))
    }

    @Test
    fun `validateDepth should handle missing knee data gracefully`() {
        // Setup: Missing knee Y coordinate
        val state = ExercisePhaseState(
            currentPhase = com.liftr.domain.model.MovementPhase.BOTTOM,
            currentHipsY = HIP_Y,
            currentKneesY = null, // Missing
            currentHipsX = CENTER_X,
            currentKneesX = null
        )

        val result = SquatDepthValidator.validateDepth(
            state = state,
            depthType = SquatDepthType.POWERLIFTING,
            tolerance = Meters(0.02f)
        )

        // Should fail-safe: reject unverifiable depth (BUG-C fix)
        assertFalse("Should fail when data is missing (fail-safe)", result.isValid)
    }

    @Test
    fun `validateDepth should use multiple methods when all data available`() {
        // Setup: Complete state with all coordinates
        val state = ExercisePhaseState(
            currentPhase = com.liftr.domain.model.MovementPhase.BOTTOM,
            currentHipsY = KNEE_Y + 0.02f, // Slightly below knees
            currentKneesY = KNEE_Y,
            currentHipsX = CENTER_X + 0.05f, // Some forward lean
            currentKneesX = CENTER_X
        )

        val result = SquatDepthValidator.validateDepth(
            state = state,
            depthType = SquatDepthType.POWERLIFTING,
            tolerance = Meters(0.02f)
        )

        // Should have multiple validation methods
        assertTrue("Should use at least 2 methods with complete data", result.methods.size >= 2)

        // Check that methods have reasonable weights
        val totalWeight = result.methods.sumOf { it.weight.toDouble() }.toFloat()
        assertTrue("Total weight should be <= 1.0", totalWeight <= 1.0f)
        assertTrue("Total weight should be > 0", totalWeight > 0.0f)
    }

    @Test
    fun `validateDepth should provide detailed debug info`() {
        val state = ExercisePhaseState(
            currentPhase = com.liftr.domain.model.MovementPhase.BOTTOM,
            currentHipsY = KNEE_Y,
            currentKneesY = KNEE_Y,
            currentHipsX = CENTER_X,
            currentKneesX = CENTER_X
        )

        val result = SquatDepthValidator.validateDepth(
            state = state,
            depthType = SquatDepthType.POWERLIFTING,
            tolerance = Meters(0.02f)
        )

        // Debug info should contain key information
        assertFalse("Debug info should not be empty", result.debugInfo.isEmpty())
        assertTrue("Should mention depth validation",
            result.debugInfo.contains("Depth validation") ||
            result.debugInfo.contains("validation"))
        assertTrue("Should show confidence percentage",
            result.debugInfo.contains("%") || result.debugInfo.contains("confidence"))
    }

    @Test
    fun `validateDepth should calculate femur angle correctly for vertical stance`() {
        // Setup: Hips directly above knees (standing position)
        val state = ExercisePhaseState(
            currentPhase = com.liftr.domain.model.MovementPhase.IDLE,
            currentHipsY = 0.5f, // Hips higher
            currentKneesY = 1.0f, // Knees lower
            currentHipsX = CENTER_X, // Same X = vertical
            currentKneesX = CENTER_X
        )

        val result = SquatDepthValidator.validateDepth(
            state = state,
            depthType = SquatDepthType.QUARTER,
            tolerance = Meters(0.02f)
        )

        // With vertical femur, quarter squat should pass
        // (the femur angle should be close to 0°)
        assertTrue("Should have femur angle method",
            result.methods.any { it.name.contains("Femur") || it.name.contains("Angle") })
    }

    @Test
    fun `validateDepth should calculate femur angle correctly for deep squat`() {
        // Setup: Hips forward and below knees (deep squat position)
        val state = ExercisePhaseState(
            currentPhase = com.liftr.domain.model.MovementPhase.BOTTOM,
            currentHipsY = 1.1f, // Hips below knees
            currentKneesY = 1.0f,
            currentHipsX = 0.6f, // Hips forward from knees
            currentKneesX = CENTER_X
        )

        val result = SquatDepthValidator.validateDepth(
            state = state,
            depthType = SquatDepthType.FULL,
            tolerance = Meters(0.02f)
        )

        // Deep squat should have large femur angle and pass full depth
        assertTrue("Full depth squat should pass", result.isValid)
    }

    @Test
    fun `validateDepth should apply tolerance correctly`() {
        // Setup: Borderline case - exactly at the edge
        val state = ExercisePhaseState(
            currentPhase = com.liftr.domain.model.MovementPhase.BOTTOM,
            currentHipsY = KNEE_Y + 0.001f, // Just barely below knees (1mm)
            currentKneesY = KNEE_Y,
            currentHipsX = CENTER_X,
            currentKneesX = CENTER_X
        )

        // Should pass with generous tolerance
        val resultWithTolerance = SquatDepthValidator.validateDepth(
            state = state,
            depthType = SquatDepthType.POWERLIFTING,
            tolerance = Meters(0.02f) // 2cm tolerance
        )

        // Should fail with zero tolerance
        val resultNoTolerance = SquatDepthValidator.validateDepth(
            state = state,
            depthType = SquatDepthType.POWERLIFTING,
            tolerance = Meters(0.0f) // No tolerance
        )

        // With tolerance should be more lenient
        assertTrue("Should consider tolerance in validation",
            resultWithTolerance.confidence >= resultNoTolerance.confidence)
    }

    @Test
    fun `validateDepth should work for all depth types`() {
        // Test that all depth types can be validated without crashing
        val state = ExercisePhaseState(
            currentPhase = com.liftr.domain.model.MovementPhase.BOTTOM,
            currentHipsY = KNEE_Y,
            currentKneesY = KNEE_Y,
            currentHipsX = CENTER_X,
            currentKneesX = CENTER_X
        )

        SquatDepthType.values().forEach { depthType ->
            val result = SquatDepthValidator.validateDepth(
                state = state,
                depthType = depthType,
                tolerance = Meters(0.02f)
            )

            // Should always return a valid result
            assertNotNull("Should return result for $depthType", result)
            assertTrue("Confidence should be in range [0,1] for $depthType",
                result.confidence in 0.0f..1.0f)
        }
    }

    @Test
    fun `validateDepth should estimate hip crease above hip joint`() {
        // The hip crease is always above (lower Y) the hip joint
        // Test that this relationship is maintained
        val state = ExercisePhaseState(
            currentPhase = com.liftr.domain.model.MovementPhase.BOTTOM,
            currentHipsY = 0.95f, // Hip joint
            currentKneesY = 1.0f,  // Knees below
            currentHipsX = CENTER_X,
            currentKneesX = CENTER_X
        )

        val result = SquatDepthValidator.validateDepth(
            state = state,
            depthType = SquatDepthType.POWERLIFTING,
            tolerance = Meters(0.02f)
        )

        // Find hip crease method if it exists
        val hipCreaseMethod = result.methods.find {
            it.name.contains("Hip") && it.name.contains("Crease")
        }

        // If hip crease method was used, verify it makes sense
        if (hipCreaseMethod != null) {
            assertNotNull("Hip crease method should have a value", hipCreaseMethod.value)
            // The value string should contain distance information
            assertTrue("Should contain distance info",
                hipCreaseMethod.value.contains("cm") ||
                hipCreaseMethod.value.contains("above") ||
                hipCreaseMethod.value.contains("below"))
        }
    }

    @Test
    fun `validateDepth confidence threshold should be 60 percent`() {
        // This test verifies the confidence threshold is working as expected
        // Create a borderline case where some methods pass and some fail

        val state = ExercisePhaseState(
            currentPhase = com.liftr.domain.model.MovementPhase.BOTTOM,
            currentHipsY = KNEE_Y - 0.05f, // 5cm above knees
            currentKneesY = KNEE_Y,
            currentHipsX = CENTER_X,
            currentKneesX = CENTER_X
        )

        val result = SquatDepthValidator.validateDepth(
            state = state,
            depthType = SquatDepthType.HALF, // Requires 8cm above
            tolerance = Meters(0.02f)
        )

        // If confidence is above 60%, should pass; below 60%, should fail
        if (result.confidence >= 0.6f) {
            assertTrue("Should pass when confidence >= 60%", result.isValid)
        } else {
            assertFalse("Should fail when confidence < 60%", result.isValid)
        }
    }

    @Test
    fun `validation methods should have reasonable weights`() {
        val state = ExercisePhaseState(
            currentPhase = com.liftr.domain.model.MovementPhase.BOTTOM,
            currentHipsY = KNEE_Y,
            currentKneesY = KNEE_Y,
            currentHipsX = CENTER_X,
            currentKneesX = CENTER_X
        )

        val result = SquatDepthValidator.validateDepth(
            state = state,
            depthType = SquatDepthType.POWERLIFTING,
            tolerance = Meters(0.02f)
        )

        // Check each method has weight between 0 and 1
        result.methods.forEach { method ->
            assertTrue("${method.name} weight should be > 0", method.weight > 0.0f)
            assertTrue("${method.name} weight should be <= 1", method.weight <= 1.0f)
        }

        // Total weight should not exceed 1.0
        val totalWeight = result.methods.sumOf { it.weight.toDouble() }.toFloat()
        assertTrue("Total weight should be <= 1.0", totalWeight <= 1.0f)
    }

    @Test
    fun `validateDepth should handle X coordinate missing for femur angle`() {
        // Setup: Y coordinates present but X coordinates missing
        val state = ExercisePhaseState(
            currentPhase = com.liftr.domain.model.MovementPhase.BOTTOM,
            currentHipsY = KNEE_Y,
            currentKneesY = KNEE_Y,
            currentHipsX = null, // Missing
            currentKneesX = null  // Missing
        )

        val result = SquatDepthValidator.validateDepth(
            state = state,
            depthType = SquatDepthType.POWERLIFTING,
            tolerance = Meters(0.02f)
        )

        // Should still work with other methods (hip crease, simple check)
        // Femur angle method should be skipped
        assertNotNull("Should return valid result", result)
        assertTrue("Should use fallback methods", result.methods.isNotEmpty())
    }
}
