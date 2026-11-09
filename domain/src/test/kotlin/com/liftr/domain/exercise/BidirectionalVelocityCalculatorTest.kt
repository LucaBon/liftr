package com.liftr.domain.exercise

import com.liftr.domain.exercise.BidirectionalVelocityCalculator.PositionSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BidirectionalVelocityCalculatorTest {

    companion object {
        private const val DELTA = 0.05f
    }

    private fun samples(vararg pairs: Pair<Float, Long>): List<PositionSample> =
        pairs.map { (pos, ts) -> PositionSample(positionMeters = pos, timestampMs = ts) }

    @Test
    fun `empty input returns empty list`() {
        val result = BidirectionalVelocityCalculator.calculateVelocities(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single sample yields zero velocity`() {
        val result = BidirectionalVelocityCalculator.calculateVelocities(
            samples(0.5f to 1000L)
        )

        assertEquals(1, result.size)
        assertEquals(0f, result[0].instantaneous.value, DELTA)
        assertEquals(0.5f, result[0].position.value, DELTA)
    }

    @Test
    fun `output is aligned one to one with input`() {
        val input = samples(
            0.0f to 0L,
            0.1f to 100L,
            0.2f to 200L,
            0.3f to 300L
        )

        val result = BidirectionalVelocityCalculator.calculateVelocities(input)

        assertEquals(input.size, result.size)
        result.forEachIndexed { i, v ->
            assertEquals(input[i].timestampMs, v.timestamp)
        }
    }

    @Test
    fun `240 fps velocity uses microsecond timestamps`() {
        // 2 m/s sampled every 4166.7 us: whole milliseconds alternate 4 and 5 ms.
        val periodUs = 1_000_000.0 / 240
        val input = (0..240).map { i ->
            val us = Math.round(i * periodUs)
            PositionSample(positionMeters = 2f * (i * periodUs / 1e6).toFloat(), timestampMs = us / 1000, timestampUs = us)
        }

        val result = BidirectionalVelocityCalculator.calculateVelocities(input)

        for (i in 20..220) assertEquals("sample $i", 2f, result[i].instantaneous.value, 0.01f)
    }

    @Test
    fun `constant velocity motion is recovered approximately`() {
        // Move 1 m/s: 0.1 m every 100 ms over 1 second.
        val input = (0..10).map { i ->
            PositionSample(positionMeters = 0.1f * i, timestampMs = i * 100L)
        }

        val result = BidirectionalVelocityCalculator.calculateVelocities(input)

        // Inner samples should converge near 1.0 m/s.
        val inner = result.subList(2, result.size - 2)
        val avg = inner.map { it.instantaneous.value }.average().toFloat()
        assertEquals(1.0f, avg, 0.25f)
    }

    @Test
    fun `velocities are non-negative magnitudes`() {
        // Descending then ascending (a rep): velocities should be magnitudes.
        val input = samples(
            0.0f to 0L,
            -0.2f to 200L,
            -0.4f to 400L,
            -0.2f to 600L,
            0.0f to 800L
        )

        val result = BidirectionalVelocityCalculator.calculateVelocities(input)

        result.forEach { v ->
            assertTrue("velocity magnitude should be >= 0", v.instantaneous.value >= 0f)
            assertTrue("position magnitude should be >= 0", v.position.value >= 0f)
        }
    }

    @Test
    fun `duplicate timestamps do not crash and reuse prior velocity`() {
        val input = samples(
            0.0f to 0L,
            0.1f to 100L,
            0.2f to 100L, // duplicate timestamp -> dt == 0
            0.3f to 200L
        )

        val result = BidirectionalVelocityCalculator.calculateVelocities(input)

        assertEquals(input.size, result.size)
        result.forEach { v ->
            assertTrue(v.instantaneous.value.isFinite())
        }
    }

    @Test
    fun `signed velocities keep the direction`() {
        // Up 0.5 m/s for a second, then down 0.5 m/s, at 30 fps.
        val input = (0..60).map { i ->
            val t = i / 30f
            PositionSample(if (t <= 1f) 0.5f * t else 1f - 0.5f * t, Math.round(t * 1000).toLong())
        }
        val signed = BidirectionalVelocityCalculator.signedVelocities(input)
        assertTrue(signed[15] > 0.1f)
        assertTrue(signed[45] < -0.1f)
        // Same magnitudes as the unsigned result.
        val unsigned = BidirectionalVelocityCalculator.calculateVelocities(input)
        signed.indices.forEach { assertEquals(unsigned[it].instantaneous.value, kotlin.math.abs(signed[it]), 1e-5f) }
    }

    @Test
    fun `the acceleration model follows a squat-length ramp`() {
        // Standing, then 0.6 m down at 0.5 m/s, at 30 fps.
        val input = (0..60).map { i ->
            val t = i / 30f
            PositionSample(-0.5f * (t - 0.5f).coerceIn(0f, 1.2f), Math.round(t * 1000).toLong())
        }
        val default = BidirectionalVelocityCalculator.signedVelocities(input)
        val tracked = BidirectionalVelocityCalculator.signedVelocities(
            input, measurementNoise = 1e-4f, accelerationNoise = 0.5f
        )
        // Mid-descent (t = 1.1 s): the acceleration model reads the real 0.5 m/s, the default lags far behind.
        assertEquals(-0.5f, tracked[33], 0.05f)
        assertTrue(default[33] > -0.35f)
    }
}
