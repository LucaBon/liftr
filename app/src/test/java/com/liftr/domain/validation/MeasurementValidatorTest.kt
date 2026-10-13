package com.liftr.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeasurementValidatorTest {

    private lateinit var validator: MeasurementValidator

    companion object {
        private const val STANDARD_HEIGHT = 1.8f  // 180cm
        private const val STANDARD_TOTAL_HEIGHT_NORM = 0.8f  // Normalized height in frame
    }

    @Before
    fun setup() {
        validator = MeasurementValidator()
    }

    // Hip Width Validation Tests
    @Test
    fun `should accept valid hip width within range`() {
        // Given
        val width = 0.15f  // 18.75% of height (reasonable)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM
        val userHeight = STANDARD_HEIGHT

        // When
        val result = validator.validateHipWidth(width, totalHeight, userHeight)

        // Then
        assertTrue(result.isValid)
        assertEquals(width, result.validatedValue, 0.001f)
        assertNull(result.issue)
    }

    @Test
    fun `should reject hip width below minimum and use fallback`() {
        // Given
        val width = 0.02f  // 2.5% of height (too narrow)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM
        val userHeight = STANDARD_HEIGHT

        // When
        val result = validator.validateHipWidth(width, totalHeight, userHeight)

        // Then
        assertFalse(result.isValid)
        assertEquals(0.19f * totalHeight, result.validatedValue, 0.001f)  // Standard ratio
        assertNotNull(result.issue)
        assertTrue(result.issue!!.usedFallback)
    }

    @Test
    fun `should reject hip width above maximum and use fallback`() {
        // Given
        val width = 0.30f  // 37.5% of height (too wide)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM
        val userHeight = STANDARD_HEIGHT

        // When
        val result = validator.validateHipWidth(width, totalHeight, userHeight)

        // Then
        assertFalse(result.isValid)
        assertEquals(0.19f * totalHeight, result.validatedValue, 0.001f)
        assertNotNull(result.issue)
        assertEquals("Hip Width", result.issue!!.measurement)
    }

    // Knee Width Validation Tests
    @Test
    fun `should accept valid knee width within range`() {
        // Given
        val width = 0.08f  // 10% of height (reasonable)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM
        val userHeight = STANDARD_HEIGHT

        // When
        val result = validator.validateKneeWidth(width, totalHeight, userHeight)

        // Then
        assertTrue(result.isValid)
        assertEquals(width, result.validatedValue, 0.001f)
        assertNull(result.issue)
    }

    @Test
    fun `should reject knee width below minimum and use fallback`() {
        // Given
        val width = 0.01f  // 1.25% of height (too narrow)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM
        val userHeight = STANDARD_HEIGHT

        // When
        val result = validator.validateKneeWidth(width, totalHeight, userHeight)

        // Then
        assertFalse(result.isValid)
        assertEquals(0.10f * totalHeight, result.validatedValue, 0.001f)
        assertNotNull(result.issue)
        assertEquals("Knee Width", result.issue!!.measurement)
    }

    @Test
    fun `should reject knee width above maximum and use fallback`() {
        // Given
        val width = 0.25f  // 31.25% of height (too wide)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM
        val userHeight = STANDARD_HEIGHT

        // When
        val result = validator.validateKneeWidth(width, totalHeight, userHeight)

        // Then
        assertFalse(result.isValid)
        assertEquals(0.10f * totalHeight, result.validatedValue, 0.001f)
        assertTrue(result.issue!!.usedFallback)
    }

    // Upper Arm Length Validation Tests
    @Test
    fun `should accept valid upper arm length within range`() {
        // Given
        val length = 0.16f  // 20% of height (reasonable)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM
        val userHeight = STANDARD_HEIGHT

        // When
        val result = validator.validateUpperArmLength(length, totalHeight, userHeight)

        // Then
        assertTrue(result.isValid)
        assertEquals(length, result.validatedValue, 0.001f)
        assertNull(result.issue)
    }

    @Test
    fun `should reject upper arm length below minimum and use fallback`() {
        // Given
        val length = 0.05f  // 6.25% of height (too short)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM
        val userHeight = STANDARD_HEIGHT

        // When
        val result = validator.validateUpperArmLength(length, totalHeight, userHeight)

        // Then
        assertFalse(result.isValid)
        assertEquals(0.19f * totalHeight, result.validatedValue, 0.001f)
        assertNotNull(result.issue)
        assertEquals("Upper Arm", result.issue!!.measurement)
    }

    @Test
    fun `should reject upper arm length above maximum and use fallback`() {
        // Given
        val length = 0.40f  // 50% of height (too long)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM
        val userHeight = STANDARD_HEIGHT

        // When
        val result = validator.validateUpperArmLength(length, totalHeight, userHeight)

        // Then
        assertFalse(result.isValid)
        assertEquals(0.19f * totalHeight, result.validatedValue, 0.001f)
        assertTrue(result.issue!!.usedFallback)
    }

    // Forearm Length Validation Tests
    @Test
    fun `should accept valid forearm length within range`() {
        // Given
        val length = 0.13f  // 16.25% of height (reasonable)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM
        val userHeight = STANDARD_HEIGHT

        // When
        val result = validator.validateForearmLength(length, totalHeight, userHeight)

        // Then
        assertTrue(result.isValid)
        assertEquals(length, result.validatedValue, 0.001f)
        assertNull(result.issue)
    }

    @Test
    fun `should reject forearm length below minimum and use fallback`() {
        // Given
        val length = 0.05f  // 6.25% of height (too short)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM
        val userHeight = STANDARD_HEIGHT

        // When
        val result = validator.validateForearmLength(length, totalHeight, userHeight)

        // Then
        assertFalse(result.isValid)
        assertEquals(0.16f * totalHeight, result.validatedValue, 0.001f)
        assertNotNull(result.issue)
        assertEquals("Forearm", result.issue!!.measurement)
    }

    @Test
    fun `should reject forearm length above maximum and use fallback`() {
        // Given
        val length = 0.35f  // 43.75% of height (too long)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM
        val userHeight = STANDARD_HEIGHT

        // When
        val result = validator.validateForearmLength(length, totalHeight, userHeight)

        // Then
        assertFalse(result.isValid)
        assertEquals(0.16f * totalHeight, result.validatedValue, 0.001f)
        assertTrue(result.issue!!.usedFallback)
    }

    // Shoulder Width Range Validation Tests
    @Test
    fun `should accept valid shoulder width`() {
        // Given
        val width = 0.20f  // 25% of height
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM

        // When
        val isValid = validator.validateShoulderWidthRange(width, totalHeight)

        // Then
        assertTrue(isValid)
    }

    @Test
    fun `should reject shoulder width below minimum`() {
        // Given
        val width = 0.05f  // 6.25% of height (too narrow)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM

        // When
        val isValid = validator.validateShoulderWidthRange(width, totalHeight)

        // Then
        assertFalse(isValid)
    }

    @Test
    fun `should reject shoulder width above maximum`() {
        // Given
        val width = 0.35f  // 43.75% of height (too wide)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM

        // When
        val isValid = validator.validateShoulderWidthRange(width, totalHeight)

        // Then
        assertFalse(isValid)
    }

    @Test
    fun `should accept shoulder width at minimum boundary`() {
        // Given
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM
        val width = 0.10f * totalHeight  // 10% - exactly at minimum


        // When
        val isValid = validator.validateShoulderWidthRange(width, totalHeight)

        // Then
        assertTrue(isValid)
    }

    @Test
    fun `should accept shoulder width near maximum boundary`() {
        // Given
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM
        val width = 0.39f * totalHeight  // 39% - just below maximum

        // When
        val isValid = validator.validateShoulderWidthRange(width, totalHeight)

        // Then
        assertTrue(isValid)
    }

    // Torso Height Range Validation Tests
    @Test
    fun `should accept valid torso height`() {
        // Given
        val torsoHeight = 0.24f  // 30% of total height
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM

        // When
        val isValid = validator.validateTorsoHeightRange(torsoHeight, totalHeight)

        // Then
        assertTrue(isValid)
    }

    @Test
    fun `should reject torso height below minimum`() {
        // Given
        val torsoHeight = 0.10f  // 12.5% of total height (too short)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM

        // When
        val isValid = validator.validateTorsoHeightRange(torsoHeight, totalHeight)

        // Then
        assertFalse(isValid)
    }

    @Test
    fun `should reject torso height above maximum`() {
        // Given
        val torsoHeight = 0.40f  // 50% of total height (too long)
        val totalHeight = STANDARD_TOTAL_HEIGHT_NORM

        // When
        val isValid = validator.validateTorsoHeightRange(torsoHeight, totalHeight)

        // Then
        assertFalse(isValid)
    }

    // Body Size in Frame Validation Tests
    @Test
    fun `should classify body as OPTIMAL when over 50 percent of frame`() {
        // Given
        val totalHeightNorm = 0.6f  // 60% of frame
        val imageHeight = 1920

        // When
        val result = validator.validateBodySizeInFrame(totalHeightNorm, imageHeight)

        // Then
        assertEquals(FrameSizeQuality.OPTIMAL, result.quality)
        assertEquals(60, result.percentage)
        assertEquals((0.6f * 1920).toInt(), result.pixels)
    }

    @Test
    fun `should classify body as ACCEPTABLE when 20-50 percent of frame`() {
        // Given
        val totalHeightNorm = 0.35f  // 35% of frame
        val imageHeight = 1920

        // When
        val result = validator.validateBodySizeInFrame(totalHeightNorm, imageHeight)

        // Then
        assertEquals(FrameSizeQuality.ACCEPTABLE, result.quality)
        assertEquals(35, result.percentage)
    }

    @Test
    fun `should classify body as TOO_SMALL when under 20 percent of frame`() {
        // Given
        val totalHeightNorm = 0.15f  // 15% of frame
        val imageHeight = 1920

        // When
        val result = validator.validateBodySizeInFrame(totalHeightNorm, imageHeight)

        // Then
        assertEquals(FrameSizeQuality.TOO_SMALL, result.quality)
        assertEquals(15, result.percentage)
    }

    @Test
    fun `should handle boundary at 20 percent for ACCEPTABLE`() {
        // Given
        val totalHeightNorm = 0.20f  // Exactly 20% of frame
        val imageHeight = 1920

        // When
        val result = validator.validateBodySizeInFrame(totalHeightNorm, imageHeight)

        // Then
        assertEquals(FrameSizeQuality.ACCEPTABLE, result.quality)
    }

    @Test
    fun `should handle boundary at 50 percent for OPTIMAL`() {
        // Given
        val totalHeightNorm = 0.51f  // Just over 50% of frame
        val imageHeight = 1920

        // When
        val result = validator.validateBodySizeInFrame(totalHeightNorm, imageHeight)

        // Then
        assertEquals(FrameSizeQuality.OPTIMAL, result.quality)
    }

    // Segment Heights Validation Tests
    @Test
    fun `should accept valid segment heights`() {
        // Given
        val headNeckHeight = 0.10f
        val torsoHeight = 0.25f
        val upperLegHeight = 0.20f
        val lowerLegHeight = 0.20f
        val totalHeight = 0.8f

        // When
        val isValid = validator.validateSegmentHeights(
            headNeckHeight, torsoHeight, upperLegHeight, lowerLegHeight, totalHeight
        )

        // Then
        assertTrue(isValid)
    }

    @Test
    fun `should reject when head-neck height is zero`() {
        // Given
        val headNeckHeight = 0f  // Invalid
        val torsoHeight = 0.25f
        val upperLegHeight = 0.20f
        val lowerLegHeight = 0.20f
        val totalHeight = 0.8f

        // When
        val isValid = validator.validateSegmentHeights(
            headNeckHeight, torsoHeight, upperLegHeight, lowerLegHeight, totalHeight
        )

        // Then
        assertFalse(isValid)
    }

    @Test
    fun `should reject when head-neck height is negative`() {
        // Given
        val headNeckHeight = -0.05f  // Invalid
        val torsoHeight = 0.25f
        val upperLegHeight = 0.20f
        val lowerLegHeight = 0.20f
        val totalHeight = 0.8f

        // When
        val isValid = validator.validateSegmentHeights(
            headNeckHeight, torsoHeight, upperLegHeight, lowerLegHeight, totalHeight
        )

        // Then
        assertFalse(isValid)
    }

    @Test
    fun `should reject when torso height is zero`() {
        // Given
        val headNeckHeight = 0.10f
        val torsoHeight = 0f  // Invalid
        val upperLegHeight = 0.20f
        val lowerLegHeight = 0.20f
        val totalHeight = 0.8f

        // When
        val isValid = validator.validateSegmentHeights(
            headNeckHeight, torsoHeight, upperLegHeight, lowerLegHeight, totalHeight
        )

        // Then
        assertFalse(isValid)
    }

    @Test
    fun `should reject when upper leg height is zero`() {
        // Given
        val headNeckHeight = 0.10f
        val torsoHeight = 0.25f
        val upperLegHeight = 0f  // Invalid
        val lowerLegHeight = 0.20f
        val totalHeight = 0.8f

        // When
        val isValid = validator.validateSegmentHeights(
            headNeckHeight, torsoHeight, upperLegHeight, lowerLegHeight, totalHeight
        )

        // Then
        assertFalse(isValid)
    }

    @Test
    fun `should reject when lower leg height is zero`() {
        // Given
        val headNeckHeight = 0.10f
        val torsoHeight = 0.25f
        val upperLegHeight = 0.20f
        val lowerLegHeight = 0f  // Invalid
        val totalHeight = 0.8f

        // When
        val isValid = validator.validateSegmentHeights(
            headNeckHeight, torsoHeight, upperLegHeight, lowerLegHeight, totalHeight
        )

        // Then
        assertFalse(isValid)
    }

    @Test
    fun `should reject when lower leg height exceeds 50 percent of total height`() {
        // Given
        val headNeckHeight = 0.10f
        val torsoHeight = 0.25f
        val upperLegHeight = 0.20f
        val lowerLegHeight = 0.45f  // More than 50% of total height (unrealistic)
        val totalHeight = 0.8f

        // When
        val isValid = validator.validateSegmentHeights(
            headNeckHeight, torsoHeight, upperLegHeight, lowerLegHeight, totalHeight
        )

        // Then
        assertFalse(isValid)
    }

    @Test
    fun `should accept lower leg height at exactly 50 percent of total height`() {
        // Given
        val headNeckHeight = 0.10f
        val torsoHeight = 0.25f
        val upperLegHeight = 0.20f
        val lowerLegHeight = 0.40f  // Exactly 50% of total height
        val totalHeight = 0.8f

        // When
        val isValid = validator.validateSegmentHeights(
            headNeckHeight, torsoHeight, upperLegHeight, lowerLegHeight, totalHeight
        )

        // Then
        assertTrue(isValid)
    }
}
