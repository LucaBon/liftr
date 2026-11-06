package com.liftr.vision.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputFramePacerTest {

    private fun run(fps: Double, seconds: Double, speedFactor: Double = 1.0): List<Long> {
        val pacer = OutputFramePacer()
        val n = (fps * seconds).toInt()
        // Source timestamps already real-time (slow-mo corrected): spacing = 1/(fps*speedFactor).
        return (0 until n).mapNotNull { pacer.ptsFor((it * 1_000_000.0 / (fps * speedFactor)).toLong() + 5_000_000L) }
    }

    @Test fun `120 fps 2 s decimates to at most 60 fps and spans 2 s`() {
        val pts = run(120.0, 2.0)
        assertEquals(0L, pts.first())
        assertTrue(pts.last() in 1_950_000L..2_000_000L)
        assertTrue(pts.size <= 121)
        assertTrue(pts.zipWithNext().all { (a, b) -> b > a && b - a >= 15_000L })
    }

    @Test fun `slow-mo x4 input keeps real-time duration`() {
        // 30 fps nominal file shot at 120 fps: 60 frames cover 0.5 s real time.
        val pts = run(30.0, 2.0, speedFactor = 4.0)
        assertTrue(pts.last() in 450_000L..500_000L)
        assertTrue(pts.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun `30 and 60 fps sources are not decimated`() {
        assertEquals(60, run(30.0, 2.0).size)
        assertEquals(120, run(60.0, 2.0).size)
    }

    @Test fun `output fps hint is capped`() {
        assertEquals(60, OutputFramePacer.outputFps(120f))
        assertEquals(30, OutputFramePacer.outputFps(30f))
    }
}
