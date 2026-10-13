package com.liftr.domain.model

import com.liftr.domain.value.Meters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for SquatDepthType enum and its depth validation logic.
 *
 * Tests all four squat depth variations based on biomechanics research:
 * - Quarter Squat (~135° knee flexion, explosive power)
 * - Half Squat (~90° knee flexion, athletic performance)
 * - Powerlifting (IPF standard, competition depth)
 * - Full/ATG (~140°+ knee flexion, maximum depth)
 */
class SquatDepthTypeTest {

    companion object {
        // Test coordinate system: Y-axis points down (higher Y = lower in space)
        private const val KNEE_Y = 1.0f // Knee at 1.0m
    }

    @Test
    fun `quarter squat should require hips well above knees`() {
        val depthType = SquatDepthType.QUARTER

        // Hips 20cm above knees - should pass (well above)
        assertTrue(depthType.isDepthAchieved(
            hipsY = 0.80f,
            kneesY = KNEE_Y
        ))

        // Hips 15cm above knees - should pass (at minimum)
        assertTrue(depthType.isDepthAchieved(
            hipsY = 0.85f,
            kneesY = KNEE_Y
        ))

        // Hips 14cm above knees - should pass with tolerance (avoiding floating point precision issues)
        assertTrue(depthType.isDepthAchieved(
            hipsY = 0.86f,
            kneesY = KNEE_Y,
            tolerance = Meters(0.02f)
        ))

        // Hips 10cm above knees - should fail (too deep for quarter squat)
        assertFalse(depthType.isDepthAchieved(
            hipsY = 0.90f,
            kneesY = KNEE_Y
        ))

        // Hips at knee level - should fail
        assertFalse(depthType.isDepthAchieved(
            hipsY = KNEE_Y,
            kneesY = KNEE_Y
        ))
    }

    @Test
    fun `half squat should require hips moderately above knees`() {
        val depthType = SquatDepthType.HALF

        // Hips 12cm above knees - should pass (well above)
        assertTrue(depthType.isDepthAchieved(
            hipsY = 0.88f,
            kneesY = KNEE_Y
        ))

        // Hips 8cm above knees - should pass (at minimum)
        assertTrue(depthType.isDepthAchieved(
            hipsY = 0.92f,
            kneesY = KNEE_Y
        ))

        // Hips 6cm above knees - should pass with tolerance
        assertTrue(depthType.isDepthAchieved(
            hipsY = 0.94f,
            kneesY = KNEE_Y,
            tolerance = Meters(0.02f)
        ))

        // Hips 3cm above knees - should fail (too deep for half squat)
        assertFalse(depthType.isDepthAchieved(
            hipsY = 0.97f,
            kneesY = KNEE_Y
        ))

        // Hips at knee level - should fail
        assertFalse(depthType.isDepthAchieved(
            hipsY = KNEE_Y,
            kneesY = KNEE_Y
        ))
    }

    @Test
    fun `powerlifting squat should require hips at or slightly below knee level`() {
        val depthType = SquatDepthType.POWERLIFTING

        // Hips 3cm above knees - should pass (within tolerance)
        assertTrue(depthType.isDepthAchieved(
            hipsY = 0.97f,
            kneesY = KNEE_Y
        ))

        // Hips at knee level - should pass (legal parallel)
        assertTrue(depthType.isDepthAchieved(
            hipsY = KNEE_Y,
            kneesY = KNEE_Y
        ))

        // Hips 3cm below knees - should pass (below parallel)
        assertTrue(depthType.isDepthAchieved(
            hipsY = 1.03f,
            kneesY = KNEE_Y
        ))

        // Hips 10cm below knees - should pass (deeper than required)
        assertTrue(depthType.isDepthAchieved(
            hipsY = 1.10f,
            kneesY = KNEE_Y
        ))

        // Hips 8cm above knees - should fail (insufficient depth)
        assertFalse(depthType.isDepthAchieved(
            hipsY = 0.92f,
            kneesY = KNEE_Y
        ))
    }

    @Test
    fun `full ATG squat should require hips clearly below knees`() {
        val depthType = SquatDepthType.FULL

        // Hips 10cm below knees - should pass (well below)
        assertTrue(depthType.isDepthAchieved(
            hipsY = 1.10f,
            kneesY = KNEE_Y
        ))

        // Hips 5cm below knees - should pass (at minimum)
        assertTrue(depthType.isDepthAchieved(
            hipsY = 1.05f,
            kneesY = KNEE_Y
        ))

        // Hips 4cm below knees - should pass with tolerance (avoiding floating point precision issues)
        assertTrue(depthType.isDepthAchieved(
            hipsY = 1.04f,
            kneesY = KNEE_Y,
            tolerance = Meters(0.02f)
        ))

        // Hips at knee level - should fail (not deep enough)
        assertFalse(depthType.isDepthAchieved(
            hipsY = KNEE_Y,
            kneesY = KNEE_Y
        ))

        // Hips above knees - should fail
        assertFalse(depthType.isDepthAchieved(
            hipsY = 0.95f,
            kneesY = KNEE_Y
        ))
    }

    @Test
    fun `depth types should have correct display names`() {
        assertEquals("Quarter Squat", SquatDepthType.QUARTER.displayName)
        assertEquals("Half Squat", SquatDepthType.HALF.displayName)
        assertEquals("Powerlifting (Parallel)", SquatDepthType.POWERLIFTING.displayName)
        assertEquals("Full Depth (ATG)", SquatDepthType.FULL.displayName)
    }

    @Test
    fun `depth types should have descriptive descriptions`() {
        assertTrue(SquatDepthType.QUARTER.description.contains("explosive power"))
        assertTrue(SquatDepthType.HALF.description.contains("athletic performance"))
        assertTrue(SquatDepthType.POWERLIFTING.description.contains("Competition standard"))
        assertTrue(SquatDepthType.FULL.description.contains("Maximum depth"))
    }

    @Test
    fun `fromString should parse depth types case-insensitively`() {
        assertEquals(SquatDepthType.QUARTER, SquatDepthType.fromString("QUARTER"))
        assertEquals(SquatDepthType.HALF, SquatDepthType.fromString("half"))
        assertEquals(SquatDepthType.POWERLIFTING, SquatDepthType.fromString("PoWeRlIfTiNg"))
        assertEquals(SquatDepthType.FULL, SquatDepthType.fromString("FULL"))
    }

    @Test
    fun `fromString should return default for invalid input`() {
        assertEquals(SquatDepthType.DEFAULT, SquatDepthType.fromString("invalid"))
        assertEquals(SquatDepthType.DEFAULT, SquatDepthType.fromString(""))
        assertEquals(SquatDepthType.DEFAULT, SquatDepthType.fromString("unknown"))
    }

    @Test
    fun `default depth type should be powerlifting`() {
        assertEquals(SquatDepthType.POWERLIFTING, SquatDepthType.DEFAULT)
    }

    @Test
    fun `depth requirements should be progressively stricter`() {
        // Quarter requires least depth (hips furthest above knees = most negative)
        assertTrue(SquatDepthType.QUARTER.minHipKneeDistance < 0)

        // Half requires more depth than quarter (closer to parallel)
        assertTrue(SquatDepthType.HALF.minHipKneeDistance > SquatDepthType.QUARTER.minHipKneeDistance)

        // Powerlifting requires more depth than half (around parallel)
        assertTrue(SquatDepthType.POWERLIFTING.minHipKneeDistance > SquatDepthType.HALF.minHipKneeDistance)

        // Full requires most depth (hips below knees = positive)
        assertTrue(SquatDepthType.FULL.minHipKneeDistance > 0)
    }

    @Test
    fun `tolerance should make depth validation more lenient`() {
        // Test case: hips slightly above the strict requirement
        val hipsY = 0.86f // 14cm above knees
        val kneesY = 1.0f

        // Without tolerance, should fail (needs 15cm for quarter)
        assertFalse(SquatDepthType.QUARTER.isDepthAchieved(
            hipsY = hipsY,
            kneesY = kneesY,
            tolerance = Meters(0.0f)
        ))

        // With 2cm tolerance, should pass (14cm is within 15cm - 2cm = 13cm)
        assertTrue(SquatDepthType.QUARTER.isDepthAchieved(
            hipsY = hipsY,
            kneesY = kneesY,
            tolerance = Meters(0.02f)
        ))
    }
}
