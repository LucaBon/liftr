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
}
