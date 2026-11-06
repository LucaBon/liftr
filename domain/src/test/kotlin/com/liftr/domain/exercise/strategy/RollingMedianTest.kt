package com.liftr.domain.exercise.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RollingMedianTest {

    private fun timesUs(fps: Int, count: Int) = List(count) { it * 1_000_000L / fps }

    @Test
    fun `a single frame outlier is dropped at any frame rate`() {
        for (fps in listOf(30, 60, 240)) {
            val times = timesUs(fps, fps) // one second
            val values = List(fps) { if (it == fps / 2) 5f else 1f }
            val smoothed = rollingMedian(times, values, windowMs = 200)
            assertEquals("$fps fps", List(fps) { 1f }, smoothed)
        }
    }

    @Test
    fun `a step lasting longer than half the window passes through`() {
        for (fps in listOf(30, 240)) {
            val times = timesUs(fps, fps)
            val values = List(fps) { if (it < fps / 2) 0f else 1f }
            val smoothed = rollingMedian(times, values, windowMs = 200)
            assertEquals(0f, smoothed.first(), 0f)
            assertEquals(1f, smoothed.last(), 0f)
            // The edge moves by less than half the window.
            val edgeMs = smoothed.indexOfFirst { it >= 1f } * 1000f / fps
            assertEquals("$fps fps", 500f, edgeMs, 100f)
        }
    }

    @Test
    fun `frames without a value stay null and do not count`() {
        val times = timesUs(30, 5)
        val smoothed = rollingMedianOrNull(times, listOf(1f, null, 9f, 1f, 1f), windowMs = 200)
        assertNull(smoothed[1])
        assertEquals(listOf(1f, 1f, 1f, 1f), listOf(smoothed[0], smoothed[2], smoothed[3], smoothed[4]))
    }

    @Test
    fun `median of an even count is the mean of the middle two`() {
        assertEquals(2.5f, median(listOf(4f, 1f, 3f, 2f)), 0f)
    }
}
