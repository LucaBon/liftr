package com.liftr.domain.exercise

import com.liftr.domain.model.BarPathPoint
import com.liftr.domain.model.DriftDirection
import com.liftr.domain.value.MetersPerSecond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarPathAnalyzerTest {

    companion object {
        private const val DELTA = 0.01f
        private const val START_X_CM = 50.0f
        private const val START_Y_CM = 100.0f
        private const val BASE_TIMESTAMP = 1000L
    }

    private fun point(
        xCm: Float = START_X_CM,
        yCm: Float = START_Y_CM,
        velocity: Float = 0.5f,
        timestamp: Long = BASE_TIMESTAMP
    ) = BarPathPoint(
        normalizedX = xCm / 100f,
        normalizedY = yCm / 100f,
        realWorldXCm = xCm,
        realWorldYCm = yCm,
        velocity = MetersPerSecond(velocity),
        timestamp = timestamp
    )

    // --- analyze: edge cases ---

    @Test
    fun `analyze should return zero drift and zero linearity for empty points`() {
        val result = BarPathAnalyzer.analyze(1, emptyList())

        assertEquals(0f, result.maxDriftCm, DELTA)
        assertEquals(0, result.linearityScore)
        assertEquals(DriftDirection.NONE, result.driftDirection)
        assertFalse(result.isValid)
    }

    @Test
    fun `analyze should return zero drift for single point`() {
        val result = BarPathAnalyzer.analyze(1, listOf(point()))

        assertEquals(0f, result.maxDriftCm, DELTA)
        assertEquals(0, result.linearityScore)
        assertFalse(result.isValid)
    }

    @Test
    fun `analyze should return zero linearity for two points`() {
        val points = listOf(
            point(xCm = START_X_CM, yCm = 100f),
            point(xCm = START_X_CM, yCm = 80f)
        )
        val result = BarPathAnalyzer.analyze(1, points)

        assertEquals(0, result.linearityScore)
        assertFalse(result.isValid)
    }

    @Test
    fun `analyze should be valid for three or more points`() {
        val points = listOf(
            point(yCm = 100f),
            point(yCm = 90f),
            point(yCm = 80f)
        )
        val result = BarPathAnalyzer.analyze(1, points)

        assertTrue(result.isValid)
    }

    // --- calculateMaxDriftCm ---

    @Test
    fun `calculateMaxDriftCm should return zero for perfectly vertical path`() {
        val points = listOf(
            point(xCm = START_X_CM, yCm = 100f),
            point(xCm = START_X_CM, yCm = 90f),
            point(xCm = START_X_CM, yCm = 80f)
        )

        assertEquals(0f, BarPathAnalyzer.calculateMaxDriftCm(points), DELTA)
    }

    @Test
    fun `calculateMaxDriftCm should calculate correct drift`() {
        val points = listOf(
            point(xCm = 50f, yCm = 100f),
            point(xCm = 53f, yCm = 90f),  // 3cm drift
            point(xCm = 51f, yCm = 80f),  // 1cm drift
            point(xCm = 55f, yCm = 70f)   // 5cm drift (max)
        )

        assertEquals(5f, BarPathAnalyzer.calculateMaxDriftCm(points), DELTA)
    }

    @Test
    fun `calculateMaxDriftCm should handle negative drift`() {
        val points = listOf(
            point(xCm = 50f, yCm = 100f),
            point(xCm = 44f, yCm = 90f)   // -6cm drift
        )

        assertEquals(6f, BarPathAnalyzer.calculateMaxDriftCm(points), DELTA)
    }

    // --- calculateLinearityScore ---

    @Test
    fun `calculateLinearityScore should return 100 for perfectly vertical path`() {
        val points = listOf(
            point(xCm = 50f, yCm = 100f),
            point(xCm = 50f, yCm = 90f),
            point(xCm = 50f, yCm = 80f),
            point(xCm = 50f, yCm = 70f)
        )

        assertEquals(100, BarPathAnalyzer.calculateLinearityScore(points))
    }

    @Test
    fun `calculateLinearityScore should return low score for high X variance`() {
        // Large X variation relative to Y range
        val points = listOf(
            point(xCm = 50f, yCm = 100f),
            point(xCm = 60f, yCm = 95f),
            point(xCm = 40f, yCm = 90f),
            point(xCm = 55f, yCm = 85f)
        )

        val score = BarPathAnalyzer.calculateLinearityScore(points)
        assertTrue("Score should be low for wobbly path, was $score", score < 50)
    }

    @Test
    fun `calculateLinearityScore should return intermediate score for moderate deviation`() {
        val points = listOf(
            point(xCm = 50f, yCm = 100f),
            point(xCm = 51f, yCm = 80f),
            point(xCm = 50f, yCm = 60f),
            point(xCm = 51f, yCm = 40f)
        )

        val score = BarPathAnalyzer.calculateLinearityScore(points)
        assertTrue("Score should be high for near-vertical path, was $score", score > 80)
    }

    @Test
    fun `calculateLinearityScore should return zero for fewer than 3 points`() {
        val points = listOf(
            point(xCm = 50f, yCm = 100f),
            point(xCm = 60f, yCm = 90f)
        )

        assertEquals(0, BarPathAnalyzer.calculateLinearityScore(points))
    }

    // --- calculateDriftDirection ---

    @Test
    fun `calculateDriftDirection should detect FORWARD drift`() {
        val points = listOf(
            point(xCm = 50f, yCm = 100f),
            point(xCm = 52f, yCm = 90f),
            point(xCm = 53f, yCm = 80f),
            point(xCm = 54f, yCm = 70f)
        )

        assertEquals(DriftDirection.FORWARD, BarPathAnalyzer.calculateDriftDirection(points))
    }

    @Test
    fun `calculateDriftDirection should detect BACKWARD drift`() {
        val points = listOf(
            point(xCm = 50f, yCm = 100f),
            point(xCm = 48f, yCm = 90f),
            point(xCm = 47f, yCm = 80f),
            point(xCm = 46f, yCm = 70f)
        )

        assertEquals(DriftDirection.BACKWARD, BarPathAnalyzer.calculateDriftDirection(points))
    }

    @Test
    fun `calculateDriftDirection should return NONE for drift under threshold`() {
        // Average drift < 0.5cm
        val points = listOf(
            point(xCm = 50.0f, yCm = 100f),
            point(xCm = 50.2f, yCm = 90f),
            point(xCm = 50.1f, yCm = 80f),
            point(xCm = 50.3f, yCm = 70f)
        )

        assertEquals(DriftDirection.NONE, BarPathAnalyzer.calculateDriftDirection(points))
    }

    @Test
    fun `calculateDriftDirection should return NONE for fewer than 3 points`() {
        val points = listOf(
            point(xCm = 50f, yCm = 100f),
            point(xCm = 60f, yCm = 90f)
        )

        assertEquals(DriftDirection.NONE, BarPathAnalyzer.calculateDriftDirection(points))
    }

    // --- velocityToColorArgb ---

    @Test
    fun `velocityToColorArgb should return green for high velocity`() {
        assertEquals(BarPathAnalyzer.COLOR_GREEN, BarPathAnalyzer.velocityToColorArgb(MetersPerSecond(0.80f)))
        assertEquals(BarPathAnalyzer.COLOR_GREEN, BarPathAnalyzer.velocityToColorArgb(MetersPerSecond(1.20f)))
    }

    @Test
    fun `velocityToColorArgb should return yellow for moderate velocity`() {
        assertEquals(BarPathAnalyzer.COLOR_YELLOW, BarPathAnalyzer.velocityToColorArgb(MetersPerSecond(0.60f)))
        assertEquals(BarPathAnalyzer.COLOR_YELLOW, BarPathAnalyzer.velocityToColorArgb(MetersPerSecond(0.75f)))
    }

    @Test
    fun `velocityToColorArgb should return orange for slow velocity`() {
        assertEquals(BarPathAnalyzer.COLOR_ORANGE, BarPathAnalyzer.velocityToColorArgb(MetersPerSecond(0.35f)))
        assertEquals(BarPathAnalyzer.COLOR_ORANGE, BarPathAnalyzer.velocityToColorArgb(MetersPerSecond(0.50f)))
    }

    @Test
    fun `velocityToColorArgb should return red for grinding velocity`() {
        assertEquals(BarPathAnalyzer.COLOR_RED, BarPathAnalyzer.velocityToColorArgb(MetersPerSecond(0.10f)))
        assertEquals(BarPathAnalyzer.COLOR_RED, BarPathAnalyzer.velocityToColorArgb(MetersPerSecond(0.30f)))
    }

    @Test
    fun `velocityToColorArgb should use absolute value for negative velocity`() {
        assertEquals(BarPathAnalyzer.COLOR_GREEN, BarPathAnalyzer.velocityToColorArgb(MetersPerSecond(-0.80f)))
        assertEquals(BarPathAnalyzer.COLOR_RED, BarPathAnalyzer.velocityToColorArgb(MetersPerSecond(-0.10f)))
    }

    // --- analyze: full integration ---

    @Test
    fun `analyze should produce correct metrics for a realistic rep`() {
        val points = listOf(
            point(xCm = 50.0f, yCm = 100f, velocity = 0.8f, timestamp = 1000L),
            point(xCm = 50.5f, yCm = 90f, velocity = 0.6f, timestamp = 1100L),
            point(xCm = 51.0f, yCm = 80f, velocity = 0.4f, timestamp = 1200L),
            point(xCm = 50.8f, yCm = 70f, velocity = 0.3f, timestamp = 1300L),
            point(xCm = 50.2f, yCm = 80f, velocity = 0.5f, timestamp = 1400L),
            point(xCm = 50.0f, yCm = 90f, velocity = 0.7f, timestamp = 1500L),
            point(xCm = 50.0f, yCm = 100f, velocity = 0.8f, timestamp = 1600L)
        )

        val result = BarPathAnalyzer.analyze(1, points)

        assertEquals(1, result.repNumber)
        assertTrue(result.isValid)
        assertEquals(1.0f, result.maxDriftCm, DELTA)
        assertTrue("Linearity should be high for near-vertical path, was ${result.linearityScore}",
            result.linearityScore > 70)
    }

    @Test
    fun `analyze should store correct rep number`() {
        val points = listOf(
            point(yCm = 100f),
            point(yCm = 90f),
            point(yCm = 80f)
        )

        assertEquals(3, BarPathAnalyzer.analyze(3, points).repNumber)
        assertEquals(7, BarPathAnalyzer.analyze(7, points).repNumber)
    }
}
