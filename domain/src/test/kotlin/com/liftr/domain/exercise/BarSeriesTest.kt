package com.liftr.domain.exercise

import com.liftr.domain.model.BarSample
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.vision.EndcapDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BarSeriesTest {

    private fun pose(timestampMs: Long): PoseResult =
        PoseResult(List(33) { PoseKeypoint(0.5f, 0.5f, 0f, 1f) }, timestampMs, imageWidth = 1000, imageHeight = 1000)

    private fun endcap(y: Float) = listOf(EndcapDetection(0.5f, y, 0.02f, 0.02f, 0.6f))

    private fun build(endcaps: Map<Long, List<EndcapDetection>>, frames: Int): List<BarSample?> =
        BarSeries.build(
            (0 until frames).map { pose(it * 50L) },
            endcaps,
            EndcapTracker(EndcapTracker.Config(smoothingAlpha = 1f, maxMissedFrames = 0))
        ) { y, _ -> y }

    @Test
    fun `velocity is measured within a run, not across a gap`() {
        // Frames 0..4 at y=0.5, a gap at 5..6, then 7..11 moving 0.01 per frame.
        val endcaps = (0..4).associate { it * 50L to endcap(0.5f) } +
            (7..11).associate { it * 50L to endcap(0.5f + 0.01f * (it - 7)) }
        val samples = build(endcaps, 12)

        assertNull(samples[5])
        assertNull(samples[6])
        // The still run reads zero: the jump to the second run doesn't leak into it.
        (0..4).forEach { assertEquals(0f, samples[it]!!.velocity!!, 1e-4f) }
        // The second run reads its own 0.2 units per second.
        assertEquals(0.2f, samples[9]!!.velocity!!, 0.03f)
        assertNotNull(samples[9]!!.acceleration)
        assertNull(samples[7]!!.acceleration)
    }

    @Test
    fun `a lone sample has a position but no velocity`() {
        val samples = build(mapOf(100L to endcap(0.5f)), 5)

        assertEquals(0.5f, samples[2]!!.positionMeters, 1e-6f)
        assertNull(samples[2]!!.velocity)
    }

    @Test
    fun `rep accumulator needs enough concentric coverage`() {
        val acc = BarSeries.RepAccumulator()
        val sample = BarSample(0L, 0.5f, 0.5f, 0.5f, velocity = 1f)
        repeat(4) { acc.onFrame(sample, active = true, concentric = true) }
        acc.onFrame(null, active = true, concentric = true)
        // 4 of 5 concentric frames covered: exactly the 80% needed.
        assertEquals(1f, acc.result().meanConcentricVelocity!!, 1e-6f)

        acc.onFrame(null, active = true, concentric = true)
        val short = acc.result()
        assertEquals(4f / 6f, short.concentricCoverage, 1e-6f)
        assertNull(short.meanConcentricVelocity)

        acc.reset()
        assertNull(acc.result().peakConcentricVelocity)
    }
}
