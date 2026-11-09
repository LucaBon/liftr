package com.liftr.domain.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandingScaleTest {

    private val frameUs = 33_000L

    private fun track(vararg parts: List<Float>): List<StandingScale.Sample> =
        parts.toList().flatten().mapIndexed { i, ppm -> StandingScale.Sample(i * frameUs, ppm) }

    private fun steady(ppm: Float, seconds: Float) = List((seconds * 30).toInt()) { ppm }

    private fun ramp(from: Float, to: Float, seconds: Float): List<Float> {
        val n = (seconds * 30).toInt()
        return List(n) { from + (to - from) * it / n }
    }

    /** Down and up: the span shortens from [top] to [bottom] and back. */
    private fun rep(top: Float, bottom: Float) = ramp(top, bottom, 1.2f) + ramp(bottom, top, 1f)

    @Test
    fun `walking in from close to the camera does not set the scale`() {
        val samples = track(ramp(566f, 424f, 2f), steady(424f, 3f), rep(424f, 300f), steady(424f, 1f), rep(424f, 300f))

        assertEquals(424f, StandingScale.estimate(samples)!!.pixelsPerMeter, 2f)
    }

    @Test
    fun `a long pause at the bottom is not mistaken for standing`() {
        val samples = track(
            steady(424f, 1.5f), rep(424f, 300f), steady(300f, 2.5f), rep(424f, 300f), steady(300f, 2.5f)
        )

        assertEquals(424f, StandingScale.estimate(samples)!!.pixelsPerMeter, 2f)
    }

    @Test
    fun `a brief stop close to the camera does not win over the set`() {
        val samples = track(steady(566f, 0.8f), ramp(566f, 424f, 1.5f), steady(424f, 5f))

        assertEquals(424f, StandingScale.estimate(samples)!!.pixelsPerMeter, 2f)
    }

    @Test
    fun `no still moment gives no estimate`() {
        val samples = track(ramp(566f, 300f, 2f), ramp(300f, 566f, 2f))

        assertNull(StandingScale.estimate(samples))
    }
}
