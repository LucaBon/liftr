package com.liftr.domain.exercise

import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.vision.EndcapDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndcapTrackerTest {

    private val noSmoothing = EndcapTracker.Config(smoothingAlpha = 1f)

    /** Square frame; wrists at ([wx], [wy]) unless hidden, shoulders 0.3 above them. */
    private fun pose(wx: Float, wy: Float, timestampMs: Long = 0L, wristsVisible: Boolean = true): PoseResult {
        val landmarks = MutableList(33) { PoseKeypoint(0.5f, 0.5f, 0f, 0f) }
        val wristVisibility = if (wristsVisible) 1f else 0f
        landmarks[PoseResult.LEFT_WRIST] = PoseKeypoint(wx, wy, 0f, wristVisibility)
        landmarks[PoseResult.RIGHT_WRIST] = PoseKeypoint(wx, wy, 0f, wristVisibility)
        landmarks[PoseResult.LEFT_SHOULDER] = PoseKeypoint(wx, wy - 0.3f, 0f, 1f)
        landmarks[PoseResult.RIGHT_SHOULDER] = PoseKeypoint(wx, wy - 0.3f, 0f, 1f)
        return PoseResult(landmarks, timestampMs, imageWidth = 1000, imageHeight = 1000)
    }

    private fun endcap(x: Float, y: Float, score: Float = 0.6f, size: Float = 0.05f) =
        EndcapDetection(x, y, size, size, score)

    @Test
    fun `picks the endcap closest to the wrists`() {
        val tracker = EndcapTracker(noSmoothing)
        val near = endcap(0.52f, 0.5f)
        val far = endcap(0.62f, 0.48f, score = 0.7f)

        val position = tracker.update(0L, listOf(far, near), pose(0.5f, 0.5f))

        assertEquals(0.52f, position!!.x, 1e-6f)
        assertEquals(0.5f, position.y, 1e-6f)
    }

    @Test
    fun `uses the shoulders when the wrists are hidden`() {
        val tracker = EndcapTracker(noSmoothing)
        // Shoulders at y=0.2: the endcap on the back is the one there.
        val onBack = endcap(0.5f, 0.21f)
        val low = endcap(0.5f, 0.4f)

        val position = tracker.update(0L, listOf(low, onBack), pose(0.5f, 0.5f, wristsVisible = false))

        assertEquals(0.21f, position!!.y, 1e-6f)
    }

    @Test
    fun `ignores endcaps far from the hands and low-confidence ones`() {
        val tracker = EndcapTracker(noSmoothing)
        val farAway = endcap(0.9f, 0.9f)
        val weak = endcap(0.5f, 0.5f, score = 0.1f)

        assertNull(tracker.update(0L, listOf(farAway, weak), pose(0.5f, 0.5f)))
    }

    @Test
    fun `gates out an implausible jump between frames`() {
        val tracker = EndcapTracker(noSmoothing.copy(maxAnchorDistance = 1f))
        tracker.update(0L, listOf(endcap(0.5f, 0.5f)), pose(0.5f, 0.5f))

        // 33 ms later 0.3 image heights away: ~9 image heights per second.
        val jumped = tracker.update(33L, listOf(endcap(0.5f, 0.8f)), pose(0.5f, 0.5f, 33L))

        assertTrue(jumped!!.held)
        assertEquals(0.5f, jumped.y, 1e-6f)
    }

    @Test
    fun `accepts plausible movement between frames`() {
        val tracker = EndcapTracker(noSmoothing)
        tracker.update(0L, listOf(endcap(0.5f, 0.5f)), pose(0.5f, 0.5f))

        val moved = tracker.update(33L, listOf(endcap(0.5f, 0.55f)), pose(0.5f, 0.55f, 33L))

        assertFalse(moved!!.held)
        assertEquals(0.55f, moved.y, 1e-6f)
    }

    @Test
    fun `holds the last position through a few missed frames`() {
        val tracker = EndcapTracker(noSmoothing.copy(maxMissedFrames = 3))
        tracker.update(0L, listOf(endcap(0.5f, 0.5f)), pose(0.5f, 0.5f))

        for (frame in 1..3) {
            val held = tracker.update(frame * 33L, emptyList(), pose(0.5f, 0.5f, frame * 33L))
            assertNotNull("frame $frame", held)
            assertTrue(held!!.held)
            assertEquals(0.5f, held.y, 1e-6f)
        }
    }

    @Test
    fun `returns null after too many missed frames, then starts over`() {
        val tracker = EndcapTracker(noSmoothing.copy(maxMissedFrames = 2))
        tracker.update(0L, listOf(endcap(0.5f, 0.5f)), pose(0.5f, 0.5f))
        tracker.update(33L, emptyList(), pose(0.5f, 0.5f, 33L))
        tracker.update(66L, emptyList(), pose(0.5f, 0.5f, 66L))

        assertNull(tracker.update(100L, emptyList(), pose(0.5f, 0.5f, 100L)))

        // A fresh track takes any endcap near the hands, without the old gate.
        val restarted = tracker.update(133L, listOf(endcap(0.5f, 0.7f)), pose(0.5f, 0.7f, 133L))
        assertEquals(0.7f, restarted!!.y, 1e-6f)
        assertFalse(restarted.held)
    }

    @Test
    fun `nothing to track without hands or an existing track`() {
        val tracker = EndcapTracker(noSmoothing)
        assertNull(tracker.update(0L, listOf(endcap(0.5f, 0.5f)), null))
    }

    @Test
    fun `smoothing moves part of the way toward each new measurement`() {
        val tracker = EndcapTracker(EndcapTracker.Config(smoothingAlpha = 0.5f))
        tracker.update(0L, listOf(endcap(0.5f, 0.5f)), pose(0.5f, 0.5f))

        val smoothed = tracker.update(33L, listOf(endcap(0.5f, 0.54f)), pose(0.5f, 0.54f, 33L))

        assertEquals(0.52f, smoothed!!.y, 1e-6f)
    }

    @Test
    fun `alpha 1 turns smoothing off`() {
        val tracker = EndcapTracker(noSmoothing)
        tracker.update(0L, listOf(endcap(0.5f, 0.5f)), pose(0.5f, 0.5f))

        val raw = tracker.update(33L, listOf(endcap(0.5f, 0.54f)), pose(0.5f, 0.54f, 33L))

        assertEquals(0.54f, raw!!.y, 1e-6f)
    }

    @Test
    fun `estimates pixels per meter from the sleeve size`() {
        val tracker = EndcapTracker(noSmoothing.copy(minScaleSamples = 5))
        // 0.05 of a 1000 px frame = 50 px for a 5 cm sleeve -> 1000 px/m.
        for (frame in 0 until 4) {
            tracker.update(frame * 33L, listOf(endcap(0.5f, 0.5f)), pose(0.5f, 0.5f, frame * 33L))
        }
        assertNull(tracker.pixelsPerMeter())

        tracker.update(4 * 33L, listOf(endcap(0.5f, 0.5f)), pose(0.5f, 0.5f, 4 * 33L))
        assertEquals(1000f, tracker.pixelsPerMeter()!!, 1e-3f)
    }
}
