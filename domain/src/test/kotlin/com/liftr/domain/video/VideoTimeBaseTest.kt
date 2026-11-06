package com.liftr.domain.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTimeBaseTest {

    private fun deltas(fps: Double, n: Int = 40) = List(n) { (1_000_000.0 / fps).toLong() }

    @Test
    fun `240 captured at 30 container fps scales by 8`() {
        val r = VideoTimeBase.resolve(30f, 240, null, deltas(30.0))
        assertEquals(8f, r.timeScale, 0.001f)
        assertEquals(240f, r.captureFps, 0.001f)
        assertFalse(r.isVariableSpeed)
    }

    @Test
    fun `120 from metadata at 30 container fps scales by 4`() {
        val r = VideoTimeBase.resolve(30f, null, 120f, deltas(30.0))
        assertEquals(4f, r.timeScale, 0.001f)
    }

    @Test
    fun `known capture fps wins over metadata`() {
        val r = VideoTimeBase.resolve(30f, 240, 120f, deltas(30.0))
        assertEquals(8f, r.timeScale, 0.001f)
    }

    @Test
    fun `60 at 60 is real time`() {
        val r = VideoTimeBase.resolve(60f, 60, null, deltas(60.0))
        assertEquals(1f, r.timeScale, 0.001f)
    }

    @Test
    fun `missing metadata is real time`() {
        val r = VideoTimeBase.resolve(30f, null, null, deltas(30.0))
        assertEquals(1f, r.timeScale, 0.001f)
        assertEquals(30f, r.captureFps, 0.001f)
    }

    @Test
    fun `jittery 59_9 vs 60 is real time`() {
        val r = VideoTimeBase.resolve(59.9f, null, 60f, deltas(60.0))
        assertEquals(1f, r.timeScale, 0.001f)
        assertFalse(r.isVariableSpeed)
    }

    @Test
    fun `sustained mixed deltas are variable speed`() {
        val d = deltas(30.0, 30) + deltas(240.0, 30)
        assertTrue(VideoTimeBase.resolve(30f, null, 240f, d).isVariableSpeed)
    }

    @Test
    fun `single dropped frame and jitter are not variable speed`() {
        val base = deltas(60.0, 60).toMutableList()
        base[20] = base[20] * 2 // dropped frame
        base[40] = base[40] / 2
        base[10] = base[10] + 3000
        assertFalse(VideoTimeBase.resolve(60f, 60, null, base).isVariableSpeed)
    }

    @Test
    fun `too few deltas never variable`() {
        assertFalse(VideoTimeBase.resolve(30f, null, null, listOf(33_000L, 4_000L)).isVariableSpeed)
    }
    @Test
    fun `slow motion container time maps to real time`() {
        val r = VideoTimeBase.resolve(30f, null, 240f, deltas(30.0))
        // Container frames every 33333 us are 4167 us apart in real time.
        assertEquals(0L, r.realTimeUs(0L))
        assertEquals(4_167L, r.realTimeUs(33_333L))
        assertEquals(1_000_000L, r.realTimeUs(8_000_000L))
    }

    @Test
    fun `real time video keeps its timestamps`() {
        val r = VideoTimeBase.resolve(60f, null, null, deltas(60.0))
        assertEquals(16_667L, r.realTimeUs(16_667L))
    }

    @Test
    fun `real time file with an fps drop is accepted`() {
        // 30 fps that drops to 15 fps in dim light: container time is real time.
        val d = deltas(30.0, 30) + deltas(15.0, 30)
        val r = VideoTimeBase.resolve(30f, null, null, d)
        assertTrue(r.isVariableSpeed)
        assertFalse(r.rejectsSpeedChange)
    }

    @Test
    fun `slow motion with mixed speeds is rejected`() {
        val d = deltas(30.0, 30) + deltas(240.0, 30)
        assertTrue(VideoTimeBase.resolve(30f, null, 240f, d).rejectsSpeedChange)
    }

    @Test
    fun `slow motion metadata rejects mixed speeds even when the known fps says real time`() {
        val d = deltas(30.0, 30) + deltas(240.0, 30)
        assertTrue(VideoTimeBase.resolve(30f, 30, 240f, d).rejectsSpeedChange)
    }

    @Test
    fun `constant slow motion is accepted`() {
        assertFalse(VideoTimeBase.resolve(30f, null, 240f, deltas(30.0)).rejectsSpeedChange)
    }

    @Test
    fun `slow motion frames get uniform times at the capture rate`() {
        // Measured 240 fps clip: 3 x 33.3 ms then 37.6 ms, after a 175 ms first step.
        val steps = listOf(175_000L) + List(40) { if (it % 4 == 3) 37_600L else 33_333L }
        val r = VideoTimeBase.resolve(30f, 240, null, deltas(30.0))
        var pts = 0L
        val times = steps.mapIndexed { i, d -> pts += d; r.frameRealTimeUs(i + 1, pts) }
        assertEquals(0L, r.frameRealTimeUs(0, 0))
        times.forEachIndexed { i, t -> assertEquals((i + 1) * 4166.67, t.toDouble(), 0.5) }
    }

    @Test
    fun `real time frames keep their presentation time`() {
        val r = VideoTimeBase.resolve(60f, null, null, deltas(60.0))
        assertEquals(16_900L, r.frameRealTimeUs(5, 16_900L))
    }
}
