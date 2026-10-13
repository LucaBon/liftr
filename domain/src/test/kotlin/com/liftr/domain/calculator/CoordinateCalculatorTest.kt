package com.liftr.domain.calculator

import com.liftr.domain.model.PoseKeypoint
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
    // MediaPipe normalizes x by the image width and y by the image height, so
    // calculateDistance converts dx to image-height units (dx * W / H) before
    // combining it with dy.
    @Test
    fun `should calculate distance correctly in portrait mode`() {
        // Given a 9:16 portrait frame
        val p1 = PoseKeypoint(0.2f, 0.2f, 0f, 0.9f)
        val p2 = PoseKeypoint(0.4f, 0.5f, 0f, 0.9f)

        // When
        val distance = calculator.calculateDistance(p1, p2, 16f / 9f, isPortrait = true)

        // Then dx shrinks by W/H = 9/16
        val dx = (0.4f - 0.2f) * 9f / 16f
        val dy = 0.5f - 0.2f
        assertEquals(sqrt(dx * dx + dy * dy), distance, 0.001f)
    }

    @Test
    fun `should calculate distance correctly in landscape mode`() {
        // Given a 16:9 landscape frame
        val p1 = PoseKeypoint(0.2f, 0.2f, 0f, 0.9f)
        val p2 = PoseKeypoint(0.4f, 0.5f, 0f, 0.9f)

        // When
        val distance = calculator.calculateDistance(p1, p2, 16f / 9f, isPortrait = false)

        // Then dx grows by W/H = 16/9
        val dx = (0.4f - 0.2f) * 16f / 9f
        val dy = 0.5f - 0.2f
        assertEquals(sqrt(dx * dx + dy * dy), distance, 0.001f)
    }

    @Test
    fun `should calculate distance from coordinates correctly in portrait`() {
        val distance = calculator.calculateDistance(0.2f, 0.2f, 0.4f, 0.5f, 16f / 9f, isPortrait = true)

        val dx = 0.2f * 9f / 16f
        val dy = 0.3f
        assertEquals(sqrt(dx * dx + dy * dy), distance, 0.001f)
    }

    @Test
    fun `distance on a square frame is plain Euclidean`() {
        val p1 = PoseKeypoint(0.1f, 0.2f, 0f, 0.9f)
        val p2 = PoseKeypoint(0.5f, 0.6f, 0f, 0.9f)

        val distance = calculator.calculateDistance(p1, p2, 1f, isPortrait = true)

        assertEquals(sqrt(0.4f * 0.4f + 0.4f * 0.4f), distance, 0.0001f)
    }

    @Test
    fun `the same physical length measures the same in portrait and landscape`() {
        // A 0.3 image-height-unit horizontal segment, in each orientation.
        val portrait = calculator.calculateDistance(0.2f, 0.5f, 0.2f + 0.3f * 16f / 9f, 0.5f, 16f / 9f, true)
        val landscape = calculator.calculateDistance(0.2f, 0.5f, 0.2f + 0.3f * 9f / 16f, 0.5f, 16f / 9f, false)

        assertEquals(0.3f, portrait, 0.0001f)
        assertEquals(0.3f, landscape, 0.0001f)
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
    // Horizontal distances are converted to image-height units (x W / H);
    // vertical distances already are and stay unchanged.
    @Test
    fun `should scale horizontal distance by width over height in portrait mode`() {
        val scaled = calculator.scaleForAspectRatio(0.2f, 16f / 9f, isPortrait = true, isHorizontal = true)
        assertEquals(0.2f * 9f / 16f, scaled, 0.0001f)
    }

    @Test
    fun `should not scale vertical distance in portrait mode`() {
        val distance = 0.2f
        val scaled = calculator.scaleForAspectRatio(distance, 16f / 9f, isPortrait = true, isHorizontal = false)
        assertEquals(distance, scaled, 0.001f)
    }

    @Test
    fun `should not scale vertical distance in landscape mode`() {
        val distance = 0.2f
        val scaled = calculator.scaleForAspectRatio(distance, 16f / 9f, isPortrait = false, isHorizontal = false)
        assertEquals(distance, scaled, 0.001f)
    }

    @Test
    fun `should scale horizontal distance by width over height in landscape mode`() {
        val scaled = calculator.scaleForAspectRatio(0.2f, 16f / 9f, isPortrait = false, isHorizontal = true)
        assertEquals(0.2f * 16f / 9f, scaled, 0.0001f)
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
    fun `should place head top proportionally to the body from the ankles`() {
        // A 1.90 m person: face 6.5% below the head top, ankles 4% above the floor.
        val stature = 0.9f
        val headTop = 0.05f
        val faceY = headTop + 0.065f * stature
        val anklesY = headTop + (1f - 0.04f) * stature

        val estimated = calculator.estimateHeadTop(faceY, anklesY, CoordinateCalculator.ANKLE_HEIGHT_RATIO)

        assertEquals(headTop, estimated, 0.0001f)
    }

    @Test
    fun `should place head top proportionally to the body from the knees`() {
        val stature = 0.5f
        val headTop = 0.2f
        val faceY = headTop + 0.065f * stature
        val kneesY = headTop + (1f - 0.285f) * stature

        val estimated = calculator.estimateHeadTop(faceY, kneesY, CoordinateCalculator.KNEE_HEIGHT_RATIO)

        assertEquals(headTop, estimated, 0.0001f)
    }

    @Test
    fun `head top offset should scale with body size, not frame size`() {
        // Same proportions at half the size: the offset halves too.
        val big = 0.1f - calculator.estimateHeadTop(0.1f, 0.9f, CoordinateCalculator.ANKLE_HEIGHT_RATIO)
        val small = 0.1f - calculator.estimateHeadTop(0.1f, 0.5f, CoordinateCalculator.ANKLE_HEIGHT_RATIO)

        assertEquals(big / 2f, small, 0.0001f)
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
