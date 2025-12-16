package com.lifthelper.domain.calculator

import com.lifthelper.domain.model.PoseKeypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

class CoordinateCalculatorTest {

    private lateinit var calculator: CoordinateCalculator

    @Before
    fun setup() {
        calculator = CoordinateCalculator()
    }

    // AspectRatio Tests
    @Test
    fun `should calculate portrait aspect ratio correctly`() {
        // Given
        val imageWidth = 1080
        val imageHeight = 1920

        // When
        val result = calculator.calculateAspectRatio(imageWidth, imageHeight)

        // Then
        assertEquals(1920f / 1080f, result.ratio, 0.001f)
        assertTrue(result.isPortrait)
    }

    @Test
    fun `should calculate landscape aspect ratio correctly`() {
        // Given
        val imageWidth = 1920
        val imageHeight = 1080

        // When
        val result = calculator.calculateAspectRatio(imageWidth, imageHeight)

        // Then
        assertEquals(1920f / 1080f, result.ratio, 0.001f)
        assertFalse(result.isPortrait)
    }

    @Test
    fun `should calculate square aspect ratio correctly`() {
        // Given
        val imageWidth = 1080
        val imageHeight = 1080

        // When
        val result = calculator.calculateAspectRatio(imageWidth, imageHeight)

        // Then
        assertEquals(1.0f, result.ratio, 0.001f)
        assertFalse(result.isPortrait)
    }

    // Y-Axis Inversion Tests
    @Test
    fun `should detect normal Y-axis orientation`() {
        // Given - head at top (y=0.2), ankles at bottom (y=0.9)
        val landmarks = createLandmarks(
            headY = 0.2f,
            anklesY = 0.9f
        )

        // When
        val isInverted = calculator.detectYAxisInversion(landmarks)

        // Then
        assertFalse(isInverted)
    }

    @Test
    fun `should detect inverted Y-axis orientation`() {
        // Given - head at bottom (y=0.9), ankles at top (y=0.2)
        val landmarks = createLandmarks(
            headY = 0.9f,
            anklesY = 0.2f
        )

        // When
        val isInverted = calculator.detectYAxisInversion(landmarks)

        // Then
        assertTrue(isInverted)
    }

    @Test
    fun `should use knees when ankles not visible`() {
        // Given - ankles have low visibility
        val landmarks = createLandmarks(
            headY = 0.2f,
            kneesY = 0.7f,
            anklesY = 0.9f,
            anklesVisibility = 0.3f  // Low visibility
        )

        // When
        val isInverted = calculator.detectYAxisInversion(landmarks)

        // Then
        assertFalse(isInverted)
    }

    @Test
    fun `should return false when head landmarks not visible`() {
        // Given - head with low visibility
        val landmarks = createLandmarks(
            headY = 0.2f,
            anklesY = 0.9f,
            headVisibility = 0.3f  // Low visibility
        )

        // When
        val isInverted = calculator.detectYAxisInversion(landmarks)

        // Then
        assertFalse(isInverted)
    }

    // Y-Axis Normalization Tests
    @Test
    fun `should flip Y coordinates when inverted`() {
        // Given - inverted coordinates
        val landmarks = createLandmarks(
            headY = 0.9f,
            anklesY = 0.1f
        )

        // When
        val normalized = calculator.normalizeYAxis(landmarks)

        // Then
        // Head Y should be flipped from 0.9 to 0.1
        val headY = normalized[0].y
        assertEquals(0.1f, headY, 0.001f)

        // Ankles Y should be flipped from 0.1 to 0.9
        val anklesY = normalized[27].y
        assertEquals(0.9f, anklesY, 0.001f)
    }

    @Test
    fun `should not flip Y coordinates when not inverted`() {
        // Given - normal coordinates
        val landmarks = createLandmarks(
            headY = 0.2f,
            anklesY = 0.9f
        )

        // When
        val normalized = calculator.normalizeYAxis(landmarks)

        // Then
        assertEquals(landmarks[0].y, normalized[0].y, 0.001f)
        assertEquals(landmarks[27].y, normalized[27].y, 0.001f)
    }

    // Distance Calculation Tests
    @Test
    fun `should calculate distance correctly in portrait mode`() {
        // Given
        val p1 = PoseKeypoint(0.2f, 0.2f, 0f, 0.9f)
        val p2 = PoseKeypoint(0.4f, 0.5f, 0f, 0.9f)
        val aspectRatio = 16f / 9f
        val isPortrait = true

        // When
        val distance = calculator.calculateDistance(p1, p2, aspectRatio, isPortrait)

        // Then
        val dx = (0.4f - 0.2f) * aspectRatio
        val dy = 0.5f - 0.2f
        val expected = sqrt(dx * dx + dy * dy)
        assertEquals(expected, distance, 0.001f)
    }

    @Test
    fun `should calculate distance correctly in landscape mode`() {
        // Given
        val p1 = PoseKeypoint(0.2f, 0.2f, 0f, 0.9f)
        val p2 = PoseKeypoint(0.4f, 0.5f, 0f, 0.9f)
        val aspectRatio = 16f / 9f
        val isPortrait = false

        // When
        val distance = calculator.calculateDistance(p1, p2, aspectRatio, isPortrait)

        // Then
        val dx = 0.4f - 0.2f
        val dy = (0.5f - 0.2f) * aspectRatio
        val expected = sqrt(dx * dx + dy * dy)
        assertEquals(expected, distance, 0.001f)
    }

    @Test
    fun `should calculate distance from coordinates correctly in portrait`() {
        // Given
        val x1 = 0.2f
        val y1 = 0.2f
        val x2 = 0.4f
        val y2 = 0.5f
        val aspectRatio = 16f / 9f
        val isPortrait = true

        // When
        val distance = calculator.calculateDistance(x1, y1, x2, y2, aspectRatio, isPortrait)

        // Then
        val dx = (x2 - x1) * aspectRatio
        val dy = y2 - y1
        val expected = sqrt(dx * dx + dy * dy)
        assertEquals(expected, distance, 0.001f)
    }

    @Test
    fun `should handle zero distance correctly`() {
        // Given
        val p1 = PoseKeypoint(0.5f, 0.5f, 0f, 0.9f)
        val p2 = PoseKeypoint(0.5f, 0.5f, 0f, 0.9f)
        val aspectRatio = 16f / 9f
        val isPortrait = true

        // When
        val distance = calculator.calculateDistance(p1, p2, aspectRatio, isPortrait)

        // Then
        assertEquals(0f, distance, 0.001f)
    }

    // Aspect Ratio Scaling Tests
    @Test
    fun `should scale horizontal distance in portrait mode`() {
        // Given
        val distance = 0.2f
        val aspectRatio = 16f / 9f
        val isPortrait = true
        val isHorizontal = true

        // When
        val scaled = calculator.scaleForAspectRatio(distance, aspectRatio, isPortrait, isHorizontal)

        // Then
        assertEquals(distance * aspectRatio, scaled, 0.001f)
    }

    @Test
    fun `should not scale vertical distance in portrait mode`() {
        // Given
        val distance = 0.2f
        val aspectRatio = 16f / 9f
        val isPortrait = true
        val isHorizontal = false

        // When
        val scaled = calculator.scaleForAspectRatio(distance, aspectRatio, isPortrait, isHorizontal)

        // Then
        assertEquals(distance, scaled, 0.001f)
    }

    @Test
    fun `should scale vertical distance in landscape mode`() {
        // Given
        val distance = 0.2f
        val aspectRatio = 16f / 9f
        val isPortrait = false
        val isHorizontal = false

        // When
        val scaled = calculator.scaleForAspectRatio(distance, aspectRatio, isPortrait, isHorizontal)

        // Then
        assertEquals(distance * aspectRatio, scaled, 0.001f)
    }

    @Test
    fun `should not scale horizontal distance in landscape mode`() {
        // Given
        val distance = 0.2f
        val aspectRatio = 16f / 9f
        val isPortrait = false
        val isHorizontal = true

        // When
        val scaled = calculator.scaleForAspectRatio(distance, aspectRatio, isPortrait, isHorizontal)

        // Then
        assertEquals(distance, scaled, 0.001f)
    }

    // Average Position Tests
    @Test
    fun `should calculate average Y position correctly`() {
        // Given
        val landmarks = listOf(
            PoseKeypoint(0.5f, 0.2f, 0f, 0.9f),  // index 0
            PoseKeypoint(0.5f, 0.4f, 0f, 0.9f),  // index 1
            PoseKeypoint(0.5f, 0.6f, 0f, 0.9f)   // index 2
        )

        // When
        val avgY = calculator.averageY(landmarks, 0, 1, 2)

        // Then
        assertEquals(0.4f, avgY, 0.001f)
    }

    @Test
    fun `should exclude low visibility landmarks from Y average`() {
        // Given
        val landmarks = listOf(
            PoseKeypoint(0.5f, 0.2f, 0f, 0.9f),  // index 0 - high visibility
            PoseKeypoint(0.5f, 0.8f, 0f, 0.3f),  // index 1 - low visibility (should be excluded)
            PoseKeypoint(0.5f, 0.4f, 0f, 0.9f)   // index 2 - high visibility
        )

        // When
        val avgY = calculator.averageY(landmarks, 0, 1, 2)

        // Then
        // Should average only indices 0 and 2 (0.2 + 0.4) / 2 = 0.3
        assertEquals(0.3f, avgY, 0.001f)
    }

    @Test
    fun `should calculate average X position correctly`() {
        // Given
        val landmarks = listOf(
            PoseKeypoint(0.2f, 0.5f, 0f, 0.9f),  // index 0
            PoseKeypoint(0.4f, 0.5f, 0f, 0.9f),  // index 1
            PoseKeypoint(0.6f, 0.5f, 0f, 0.9f)   // index 2
        )

        // When
        val avgX = calculator.averageX(landmarks, 0, 1, 2)

        // Then
        assertEquals(0.4f, avgX, 0.001f)
    }

    @Test
    fun `should exclude low visibility landmarks from X average`() {
        // Given
        val landmarks = listOf(
            PoseKeypoint(0.2f, 0.5f, 0f, 0.9f),  // index 0 - high visibility
            PoseKeypoint(0.8f, 0.5f, 0f, 0.3f),  // index 1 - low visibility (should be excluded)
            PoseKeypoint(0.4f, 0.5f, 0f, 0.9f)   // index 2 - high visibility
        )

        // When
        val avgX = calculator.averageX(landmarks, 0, 1, 2)

        // Then
        // Should average only indices 0 and 2 (0.2 + 0.4) / 2 = 0.3
        assertEquals(0.3f, avgX, 0.001f)
    }

    // Head Top Estimation Tests
    @Test
    fun `should estimate head top correctly for normal Y-axis`() {
        // Given
        val headFaceY = 0.2f
        val isYAxisInverted = false

        // When
        val headTop = calculator.estimateHeadTop(headFaceY, isYAxisInverted)

        // Then
        assertEquals(0.2f - 0.065f, headTop, 0.001f)
    }

    @Test
    fun `should estimate head top correctly for inverted Y-axis`() {
        // Given
        val headFaceY = 0.8f
        val isYAxisInverted = true

        // When
        val headTop = calculator.estimateHeadTop(headFaceY, isYAxisInverted)

        // Then
        assertEquals(0.8f + 0.065f, headTop, 0.001f)
    }

    // Helper function to create test landmarks
    private fun createLandmarks(
        headY: Float = 0.2f,
        kneesY: Float = 0.7f,
        anklesY: Float = 0.9f,
        headVisibility: Float = 0.9f,
        anklesVisibility: Float = 0.9f
    ): List<PoseKeypoint> {
        val landmarks = MutableList(33) { PoseKeypoint(0.5f, 0.5f, 0f, 0.3f) }

        // Set head landmarks (nose=0, left_ear=7, right_ear=8)
        landmarks[0] = PoseKeypoint(0.5f, headY, 0f, headVisibility)
        landmarks[7] = PoseKeypoint(0.4f, headY, 0f, headVisibility)
        landmarks[8] = PoseKeypoint(0.6f, headY, 0f, headVisibility)

        // Set knee landmarks (25, 26)
        landmarks[25] = PoseKeypoint(0.4f, kneesY, 0f, 0.9f)
        landmarks[26] = PoseKeypoint(0.6f, kneesY, 0f, 0.9f)

        // Set ankle landmarks (27, 28)
        landmarks[27] = PoseKeypoint(0.4f, anklesY, 0f, anklesVisibility)
        landmarks[28] = PoseKeypoint(0.6f, anklesY, 0f, anklesVisibility)

        return landmarks
    }
}
