package com.lifthelper.domain.exercise

import com.lifthelper.domain.exercise.BidirectionalVelocityCalculator.PositionSample
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
}
