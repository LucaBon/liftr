package com.liftr.domain.exercise

import com.liftr.domain.usecase.AnalyzeVideoUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RepSegmenterTest {

    private val frameUs = 33_333L
    private val minDescent = 0.30f

    /** A bar height trajectory sampled at 30 fps, built from timed pieces. */
    private class Track(start: Float) {
        val heights = mutableListOf(start)
        val last get() = heights.last()

        /** Stays at the current height for [seconds]. */
        fun hold(seconds: Float) = if (seconds > 0f) moveTo(last, seconds) else this

        /** Moves at constant speed to [height] over [seconds]. */
        fun moveTo(height: Float, seconds: Float): Track {
            val from = last
            val n = Math.round(seconds * 30).coerceAtLeast(1)
            for (k in 1..n) heights += from + (height - from) * k / n
            return this
        }
    }

    private fun samples(heights: List<Float?>): List<RepSegmenter.Sample> {
        val times = heights.indices.map { it * frameUs }
        val held = AnalyzeVideoUseCase.fillTrackGaps(times, heights, AnalyzeVideoUseCase.MAX_INTERPOLATED_GAP_US).held
        val bridged = AnalyzeVideoUseCase.fillTrackGaps(times, heights, Long.MAX_VALUE).positions
        val velocities = BidirectionalVelocityCalculator.signedVelocities(
            times.mapIndexed { i, t -> BidirectionalVelocityCalculator.PositionSample(bridged[i], t / 1_000, t) },
            measurementNoise = 1e-4f,
            accelerationNoise = 0.5f
        )
        return times.indices.map { RepSegmenter.Sample(times[it], bridged[it], velocities[it], !held[it]) }
    }

    private fun segment(heights: List<Float?>) = RepSegmenter.segment(samples(heights), minDescent)

    private fun seconds(index: Int) = index * frameUs / 1e6f

    /** Standing at 1.5 m, down 0.6 m at 0.5 m/s, [pause] s at the bottom, up at 0.6 m/s, standing again. */
    private fun squat(track: Track, pause: Float = 0f, top: Float = track.last) =
        track.moveTo(top - 0.6f, 1.2f).hold(pause).moveTo(top, 1.0f).hold(1.0f)

    @Test
    fun `a rep without a pause`() {
        val track = Track(1.5f).hold(1f)
        squat(track)
        val reps = segment(track.heights)

        assertEquals(1, reps.size)
        val rep = reps[0]
        assertEquals(0.6f, rep.rangeOfMotion, 0.01f)
        assertEquals(0L, rep.pauseMs)
        assertEquals(1.2f, rep.eccentricDurationS, 0.1f)
        assertEquals(0.5f, rep.meanEccentricVelocity, 0.05f)
        assertEquals(1.0f, rep.concentricDurationS, 0.15f)
        assertEquals(0.6f, rep.meanConcentricVelocity, 0.08f)
        // A constant 0.6 m/s ascent peaks near it, not at a spike.
        assertTrue(rep.peakConcentricVelocity in 0.5f..0.8f)
        assertEquals(1.5f, rep.standingHeight, 0.005f)
        // The descent starts when the bar leaves the top, at 1 s.
        assertEquals(1f, seconds(rep.eccentricStart), 0.05f)
        assertEquals(2.2f, seconds(rep.bottom), 0.05f)
    }

    @Test
    fun `a paused rep times its pause and keeps it out of both phases`() {
        val track = Track(1.5f).hold(1f)
        squat(track, pause = 1f)
        val reps = segment(track.heights)

        assertEquals(1, reps.size)
        val rep = reps[0]
        assertEquals(1000f, rep.pauseMs.toFloat(), 40f)
        assertEquals(0.5f, rep.meanEccentricVelocity, 0.05f)
        assertEquals(0.6f, rep.meanConcentricVelocity, 0.08f)
        assertEquals(1.0f, rep.concentricDurationS, 0.15f)
        assertTrue(rep.pauseStart < rep.concentricStart)
        assertEquals(2.2f, seconds(rep.pauseStart), 0.05f)
        assertEquals(3.2f, seconds(rep.concentricStart), 0.05f)
    }

    @Test
    fun `two reps, the second from a lower re-anchored top`() {
        val track = Track(1.5f).hold(1f)
        squat(track, pause = 0.6f)
        // Between reps the lifter settles 4 cm lower.
        track.moveTo(1.46f, 0.5f).hold(1f)
        squat(track, pause = 0.6f)
        val reps = segment(track.heights)

        assertEquals(2, reps.size)
        assertEquals(1.5f, reps[0].standingHeight, 0.005f)
        assertEquals(1.46f, reps[1].standingHeight, 0.005f)
        assertEquals(0.6f, reps[0].rangeOfMotion, 0.01f)
        assertEquals(0.6f, reps[1].rangeOfMotion, 0.01f)
        assertTrue(reps[0].concentricEnd < reps[1].eccentricStart)
    }

    @Test
    fun `the unrack and walkout before the first rep do not count or move its top`() {
        // In the rack, lifted 6 cm off the hooks, three steps back with small
        // dips, then standing still before the rep.
        val track = Track(1.42f).hold(1f).moveTo(1.48f, 0.4f).hold(0.5f)
        repeat(3) { track.moveTo(1.40f, 0.3f).moveTo(1.48f, 0.3f) }
        track.hold(1f)
        squat(track)
        // Re-rack: walk forward and lower the bar onto the hooks for good.
        track.moveTo(1.30f, 0.6f).hold(1.5f)
        val reps = segment(track.heights)

        assertEquals(1, reps.size)
        assertEquals(1.48f, reps[0].standingHeight, 0.005f)
        assertEquals(0.6f, reps[0].rangeOfMotion, 0.01f)
    }

    @Test
    fun `noise does not split or add reps`() {
        val track = Track(1.5f).hold(1f)
        squat(track, pause = 0.5f)
        squat(track)
        // ±4 mm: a pixel of the plate tracker's 360 px frame.
        val random = Random(7)
        val noisy = track.heights.map { it + (random.nextFloat() - 0.5f) * 0.008f }
        val reps = segment(noisy)

        assertEquals(2, reps.size)
        assertEquals(500f, reps[0].pauseMs.toFloat(), 70f)
        for (rep in reps) {
            assertEquals(0.6f, rep.rangeOfMotion, 0.02f)
            assertEquals(0.5f, rep.meanEccentricVelocity, 0.07f)
            assertEquals(0.6f, rep.meanConcentricVelocity, 0.1f)
            assertTrue(rep.peakConcentricVelocity < 1f)
        }
    }

    @Test
    fun `a short tracking gap is interpolated and the rep still counts`() {
        val track = Track(1.5f).hold(1f)
        squat(track)
        // 150 ms lost halfway down.
        val gapped = track.heights.mapIndexed { i, h -> if (seconds(i) in 1.5f..1.65f) null else h }
        val reps = segment(gapped)

        assertEquals(1, reps.size)
        assertEquals(0.6f, reps[0].rangeOfMotion, 0.01f)
        assertEquals(0.5f, reps[0].meanEccentricVelocity, 0.05f)
    }

    @Test
    fun `a rep through a long gap is dropped, not invented`() {
        val track = Track(1.5f).hold(1f)
        squat(track)
        // The bar is lost from halfway down to halfway up.
        val gapped = track.heights.mapIndexed { i, h -> if (seconds(i) in 1.6f..2.7f) null else h }

        assertEquals(0, segment(gapped).size)
    }

    @Test
    fun `a dip shallower than the depth type is not a rep`() {
        val track = Track(1.5f).hold(1f).moveTo(1.3f, 0.5f).moveTo(1.5f, 0.5f).hold(1f)

        assertEquals(0, segment(track.heights).size)
    }

    @Test
    fun `no standing plateau, no reference, no rep`() {
        // Moving the whole time: nothing tells where standing is.
        val track = Track(1.5f).moveTo(0.9f, 1.2f).moveTo(1.5f, 1.0f)

        assertEquals(0, segment(track.heights).size)
    }

    @Test
    fun `a bench set times the chest pause on the rep that has one`() {
        // Bar at 1.0 m: down 0.3 m in 1 s, up in 0.8 s; the second rep rests 0.5 s on the chest.
        val track = Track(1.0f).hold(1f)
        for (pause in listOf(0f, 0.5f, 0f)) {
            track.moveTo(0.7f, 1.0f).hold(pause).moveTo(1.0f, 0.8f).hold(1f)
        }
        val reps = RepSegmenter.segment(samples(track.heights), 0.15f, RepSegmenter.Direction.DOWN_FIRST)

        assertEquals(3, reps.size)
        assertEquals(0f, reps[0].pauseMs.toFloat(), 100f)
        assertEquals(500f, reps[1].pauseMs.toFloat(), 70f)
        assertEquals(0f, reps[2].pauseMs.toFloat(), 100f)
        for (rep in reps) {
            assertEquals(0.3f, rep.rangeOfMotion, 0.01f)
            assertEquals(0.3f, rep.meanEccentricVelocity, 0.04f)
            assertEquals(0.375f, rep.meanConcentricVelocity, 0.06f)
            assertTrue(rep.peakConcentricVelocity in 0.33f..0.55f)
        }
    }

    /** On the floor 1 s, then three 0.5 m pulls of 1 s with 1 s lockouts and 1 s lowerings. */
    private fun deadliftTrack(): Track {
        val track = Track(0f).hold(1f)
        track.moveTo(0.5f, 1f).hold(1f).moveTo(0f, 1f)
        // Rep 2: a dead stop, 1 s on the floor first.
        track.hold(1f).moveTo(0.5f, 1f).hold(1f).moveTo(0f, 1f)
        // Rep 3: touch-and-go.
        track.moveTo(0.5f, 1f).hold(1f).moveTo(0f, 1f).hold(1f)
        return track
    }

    @Test
    fun `a deadlift set reads the pull as the concentric and the floor rest as the pause`() {
        val reps = RepSegmenter.segment(samples(deadliftTrack().heights), 0.30f, RepSegmenter.Direction.UP_FIRST)

        assertEquals(3, reps.size)
        assertEquals(0f, reps[0].pauseMs.toFloat(), 1f)
        assertEquals(1000f, reps[1].pauseMs.toFloat(), 150f)
        assertEquals(0f, reps[2].pauseMs.toFloat(), 150f)
        for (rep in reps) {
            assertEquals(0.5f, rep.rangeOfMotion, 0.02f)
            assertEquals(1.0f, rep.concentricDurationS, 0.15f)
            assertEquals(0.5f, rep.meanConcentricVelocity, 0.07f)
            assertTrue(rep.peakConcentricVelocity in 0.45f..0.75f)
            assertEquals(1.0f, rep.eccentricDurationS, 0.15f)
            assertEquals(0.5f, rep.meanEccentricVelocity, 0.07f)
            // In time order: the pull, the lockout, the lowering.
            assertTrue(rep.eccentricStart < rep.pauseStart && rep.pauseStart <= rep.concentricStart)
            assertTrue(rep.concentricStart < rep.concentricEnd)
        }
        // The first pull starts when the bar leaves the floor, after 1 s.
        assertEquals(1f, seconds(reps[0].eccentricStart), 0.05f)
    }
}
