package com.liftr.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.exp

class JumpFlightValidatorTest {

    /** A 2.5 m/s flight sampled at 30 fps, with [height] mapping (τ, true height) to the measured one. */
    private fun flight(noiseM: Double = 0.003, height: (Double, Double) -> Double = { _, z -> z }): Pair<List<Double>, List<Double>> {
        val random = Random(7)
        val taus = (0 until 15).map { it / 30.0 }
        val heights = taus.map { tau -> height(tau, 2.5 * tau - 9.81 * tau * tau / 2) + noiseM * random.nextGaussian() }
        return taus to heights
    }

    @Test
    fun `free fall passes`() {
        val (taus, heights) = flight()

        val check = JumpFlightValidator.check(taus, heights)!!

        assertEquals(9.81f, check.gravityEstimate, 0.5f)
        assertTrue(check.gravityOk)
        assertTrue(check.residualsOk)
        assertTrue(check.passed)
    }

    @Test
    fun `a wrong scale shows up as wrong gravity`() {
        // 15% too many cm per unit of image.
        val (taus, heights) = flight { _, z -> 1.15 * z }

        val check = JumpFlightValidator.check(taus, heights)!!

        assertEquals(9.81f * 1.15f, check.gravityEstimate, 0.5f)
        assertFalse(check.gravityOk)
        assertFalse(check.passed)
    }

    @Test
    fun `a hump in the path makes the residuals follow a trend`() {
        // The center of mass bulging up by 3 cm for ~100 ms, as a wrong arm mass would.
        val (taus, heights) = flight { tau, z -> z + 0.03 * exp(-(tau - 0.15) * (tau - 0.15) / (2 * 0.04 * 0.04)) }

        val check = JumpFlightValidator.check(taus, heights)!!

        assertTrue(check.durbinWatson < 1.0f)
        assertTrue(check.lag1Autocorrelation > 0.3f)
        assertFalse(check.residualsOk)
    }

    @Test
    fun `a joint picked up elsewhere shows up as a step`() {
        val (taus, heights) = flight { tau, z -> if (tau > 0.3) z + 0.06 else z }

        val check = JumpFlightValidator.check(taus, heights)!!

        assertTrue(check.residualStep)
        assertFalse(check.residualsOk)
    }

    @Test
    fun `alternating jitter is noise, not a step`() {
        val (taus, heights) = flight(noiseM = 0.0) { tau, z -> z + if ((tau * 30).toInt() % 2 == 0) 0.01 else -0.01 }

        val check = JumpFlightValidator.check(taus, heights)!!

        assertFalse(check.residualStep)
        assertTrue(check.residualsOk)
    }

    @Test
    fun `too few frames are not checked`() {
        val (taus, heights) = flight()

        assertNull(JumpFlightValidator.check(taus.take(5), heights.take(5)))
    }

    @Test
    fun `a single mistracked frame is left out and the fit passes`() {
        val (taus, heights) = flight { tau, z -> if ((tau * 30).toInt() == 6) z + 0.08 else z }

        val (check, kept) = JumpFlightValidator.checkWithoutOutliers(taus, heights)!!

        assertEquals(1, check.droppedFrames)
        assertFalse(6 in kept)
        assertFalse(check.residualStep)
        assertTrue(check.passed)
    }

    @Test
    fun `a lasting step keeps every frame and stays flagged`() {
        val (taus, heights) = flight { tau, z -> if (tau > 0.3) z + 0.06 else z }

        val (check, kept) = JumpFlightValidator.checkWithoutOutliers(taus, heights)!!

        assertEquals(0, check.droppedFrames)
        assertEquals(taus.size, kept.size)
        assertTrue(check.residualStep)
    }

    @Test
    fun `a clean flight keeps every frame`() {
        val (taus, heights) = flight()

        val (check, kept) = JumpFlightValidator.checkWithoutOutliers(taus, heights)!!

        assertEquals(0, check.droppedFrames)
        assertEquals(taus.size, kept.size)
    }
}
