package com.lifthelper.domain.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for AnthropometricData model
 *
 * Tests cover:
 * - Default ratios correctness
 * - Measurement calculations
 * - Validation logic
 * - Segment mass and COM calculations
 */
class AnthropometricDataTest {

    companion object {
        private const val USER_HEIGHT = 1.75f // 175 cm
        private const val USER_WEIGHT = 75f // 75 kg
        private const val TOLERANCE = 0.01f
    }

    @Test
    fun `getDefaultRatios should return valid anthropometric data`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT, USER_WEIGHT)

        assertNotNull(data)
        assertEquals(USER_HEIGHT, data.userHeight, 0.001f)
        assertEquals(USER_WEIGHT, data.userWeight ?: 0f, 0.001f)
        assertEquals(CalibrationQuality.FALLBACK, data.captureQuality)
    }

    @Test
    fun `default ratios should sum to approximately 1_0`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        val totalRatio = data.headHeightRatio + data.neckHeightRatio +
                        data.torsoHeightRatio + data.upperLegHeightRatio +
                        data.lowerLegHeightRatio + data.footHeightRatio

        assertEquals(1.0f, totalRatio, 0.1f) // 10% tolerance
        assertTrue("Total ratio should be valid", data.isValid())
    }

    @Test
    fun `getHeadHeight should calculate correct value`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        val expectedHeight = data.headHeightRatio * USER_HEIGHT
        assertEquals(expectedHeight, data.getHeadHeight(), 0.001f)
    }

    @Test
    fun `getTorsoHeight should calculate correct value`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        val expectedHeight = data.torsoHeightRatio * USER_HEIGHT
        assertEquals(expectedHeight, data.getTorsoHeight(), 0.001f)
    }

    @Test
    fun `getShoulderWidth should calculate correct value`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        val expectedWidth = data.shoulderWidthRatio * USER_HEIGHT
        assertEquals(expectedWidth, data.getShoulderWidth(), 0.001f)
    }

    @Test
    fun `getHipWidth should calculate correct value`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        val expectedWidth = data.hipWidthRatio * USER_HEIGHT
        assertEquals(expectedWidth, data.getHipWidth(), 0.001f)
    }

    @Test
    fun `getSegmentMass should return correct percentages`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT, USER_WEIGHT)

        val headMass = data.getSegmentMass(BodySegment.HEAD)
        val trunkMass = data.getSegmentMass(BodySegment.TRUNK)
        val thighMass = data.getSegmentMass(BodySegment.THIGH)

        assertNotNull(headMass)
        assertNotNull(trunkMass)
        assertNotNull(thighMass)

        // Head should be ~8.1% of body weight
        assertEquals(USER_WEIGHT * 0.081f, headMass!!, 0.1f)

        // Trunk should be ~49.7% of body weight
        assertEquals(USER_WEIGHT * 0.497f, trunkMass!!, 0.1f)

        // Thigh should be ~10% of body weight
        assertEquals(USER_WEIGHT * 0.100f, thighMass!!, 0.1f)
    }

    @Test
    fun `getSegmentMass should return null when weight not provided`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT, userWeight = null)

        val headMass = data.getSegmentMass(BodySegment.HEAD)
        assertNull("Segment mass should be null when weight not provided", headMass)
    }

    @Test
    fun `getSegmentCOMRatio should return correct values`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        assertEquals(0.50f, data.getSegmentCOMRatio(BodySegment.HEAD), TOLERANCE)
        assertEquals(0.43f, data.getSegmentCOMRatio(BodySegment.TRUNK), TOLERANCE)
        assertEquals(0.436f, data.getSegmentCOMRatio(BodySegment.UPPER_ARM), TOLERANCE)
        assertEquals(0.433f, data.getSegmentCOMRatio(BodySegment.THIGH), TOLERANCE)
        assertEquals(0.433f, data.getSegmentCOMRatio(BodySegment.SHANK), TOLERANCE)
    }

    @Test
    fun `isValid should return true for default ratios`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        assertTrue("Default ratios should be valid", data.isValid())
    }

    @Test
    fun `isValid should return false for invalid ratios`() {
        val data = AnthropometricData(
            headHeightRatio = 0.5f,  // Too large
            neckHeightRatio = 0.5f,  // Way off
            torsoHeightRatio = 0.1f,
            upperLegHeightRatio = 0.1f,
            lowerLegHeightRatio = 0.1f,
            footHeightRatio = 0.1f,
            shoulderWidthRatio = 0.26f,
            hipWidthRatio = 0.19f,
            kneeWidthRatio = 0.10f,
            upperArmLengthRatio = 0.19f,
            forearmLengthRatio = 0.16f,
            thighLengthRatio = 0.25f,
            shinLengthRatio = 0.23f,
            userHeight = USER_HEIGHT,
            userWeight = USER_WEIGHT
        )

        assertFalse("Invalid ratios should fail validation", data.isValid())
    }

    @Test
    fun `all segment length getters should return positive values`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        assertTrue(data.getHeadHeight() > 0)
        assertTrue(data.getNeckHeight() > 0)
        assertTrue(data.getTorsoHeight() > 0)
        assertTrue(data.getUpperLegHeight() > 0)
        assertTrue(data.getLowerLegHeight() > 0)
        assertTrue(data.getFootHeight() > 0)
    }

    @Test
    fun `all segment width getters should return positive values`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        assertTrue(data.getShoulderWidth() > 0)
        assertTrue(data.getHipWidth() > 0)
        assertTrue(data.getKneeWidth() > 0)
    }

    @Test
    fun `all limb length getters should return positive values`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        assertTrue(data.getUpperArmLength() > 0)
        assertTrue(data.getForearmLength() > 0)
        assertTrue(data.getThighLength() > 0)
        assertTrue(data.getShinLength() > 0)
    }

    @Test
    fun `thighLength should equal upperLegHeight`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        assertEquals(data.getUpperLegHeight(), data.getThighLength(), 0.001f)
    }

    @Test
    fun `shinLength should equal lowerLegHeight`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        assertEquals(data.getLowerLegHeight(), data.getShinLength(), 0.001f)
    }

    @Test
    fun `measurement issues should be empty by default`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        assertTrue("Default data should have no measurement issues",
                   data.measurementIssues.isEmpty())
    }

    @Test
    fun `alignment message should be null by default`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        assertNull("Default data should have no alignment message",
                   data.alignmentMessage)
    }

    @Test
    fun `capture timestamp should be set`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        assertTrue("Capture timestamp should be set", data.captureTimestamp > 0)
    }

    @Test
    fun `default ratios should have realistic proportions`() {
        val data = AnthropometricData.getDefaultRatios(USER_HEIGHT)

        // Shoulder width should be roughly 23-26% of height
        assertTrue("Shoulder width ratio realistic",
                   data.shoulderWidthRatio in 0.20f..0.30f)

        // Hip width should be roughly 15-22% of height
        assertTrue("Hip width ratio realistic",
                   data.hipWidthRatio in 0.15f..0.25f)

        // Torso should be roughly 25-35% of height
        assertTrue("Torso height ratio realistic",
                   data.torsoHeightRatio in 0.25f..0.35f)

        // Legs (upper + lower) should be roughly 45-55% of height
        val legRatio = data.upperLegHeightRatio + data.lowerLegHeightRatio
        assertTrue("Leg height ratio realistic",
                   legRatio in 0.45f..0.55f)
    }
}
