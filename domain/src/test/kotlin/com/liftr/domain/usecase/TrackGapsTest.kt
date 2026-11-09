package com.liftr.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackGapsTest {

    private val gap = AnalyzeVideoUseCase.MAX_INTERPOLATED_GAP_US

    /** Frames every [stepMs]. */
    private fun times(n: Int, stepMs: Long) = (0 until n).map { it * stepMs * 1000 }

    private fun assertTrack(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-4f) }
    }

    @Test
    fun `a short gap is bridged by a straight line`() {
        // 4 frames 50 ms apart: the known neighbors are 150 ms apart.
        val filled = AnalyzeVideoUseCase.fillTrackGaps(times(4, 50), listOf(1f, null, null, 1.3f), gap)
        assertTrack(listOf(1f, 1.1f, 1.2f, 1.3f), filled.positions)
        assertEquals(2, filled.missing)
    }

    @Test
    fun `a long gap holds the last position`() {
        // 6 frames 50 ms apart: the known neighbors are 250 ms apart.
        val filled = AnalyzeVideoUseCase.fillTrackGaps(times(6, 50), listOf(1f, null, null, null, null, 1.5f), gap)
        assertTrack(listOf(1f, 1f, 1f, 1f, 1f, 1.5f), filled.positions)
    }

    @Test
    fun `leading and trailing gaps take the nearest known position`() {
        val filled = AnalyzeVideoUseCase.fillTrackGaps(times(5, 33), listOf(null, null, 0.8f, 0.9f, null), gap)
        assertTrack(listOf(0.8f, 0.8f, 0.8f, 0.9f, 0.9f), filled.positions)
    }

    @Test
    fun `no position at all gives zeros`() {
        assertTrack(listOf(0f, 0f), AnalyzeVideoUseCase.fillTrackGaps(times(2, 33), listOf(null, null), gap).positions)
    }

    @Test
    fun `a position reached faster than the cap is dropped`() {
        // 3 m/s over 50 ms allows 0.15 m; the 0.5 m jump is a landmark collapse.
        val gated = AnalyzeVideoUseCase.dropTrackJumps(times(4, 50), listOf(1f, 1.1f, 1.6f, 1.15f), 3f)
        assertEquals(listOf(1f, 1.1f, null, 1.15f), gated)
    }

    @Test
    fun `the allowance grows with the time since the last kept position`() {
        // The jump to 1.5 m would be too fast after 50 ms, but 200 ms after the
        // last kept position 0.6 m are allowed.
        val gated = AnalyzeVideoUseCase.dropTrackJumps(times(5, 50), listOf(1f, null, null, null, 1.5f), 3f)
        assertEquals(listOf(1f, null, null, null, 1.5f), gated)
    }

    @Test
    fun `lost frames merge into ranges per reason`() {
        val reasons = listOf(null, "sanity", "sanity", "sanity", null, "track jump", "sanity")
        val ranges = AnalyzeVideoUseCase.gapRanges(times(7, 100), reasons)
        assertEquals(
            listOf("0.10-0.30 s (sanity, 3 frames)", "0.50-0.50 s (track jump, 1 frames)", "0.60-0.60 s (sanity, 1 frames)"),
            ranges
        )
    }

    @Test
    fun `a jump in the pose spacing is a no pose gap`() {
        val times = listOf(0L, 33_000L, 66_000L, 166_000L, 199_000L, 232_000L)
        assertEquals(listOf("0.07-0.17 s (no pose, ~2 frames)"), AnalyzeVideoUseCase.noPoseGaps(times))
    }
}
