package com.liftr.domain.vision

import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.random.Random

class PlateTrackerTest {

    private val w = 360
    private val h = 640
    private val r = 58f
    private val plateX = 180f
    private val topY = 160f
    private val bottomY = 380f

    /** Distractor: a same-size plate low in the frame, like a foreground plate stack. */
    private val stackX = 150f
    private val stackY = 0.82f * h

    /** Two squats at 30 fps: 1 s standing, 1.5 s down, 1.5 s up, 1 s standing. */
    private fun plateY(frame: Int): Float {
        val t = (frame % 150) / 30f
        return when {
            t < 1f || t >= 4f -> topY
            else -> topY + (bottomY - topY) * (1 - cos(PI * (t - 1f) / 1.5f).toFloat()) / 2
        }
    }

    private fun render(frame: Int, plateCenter: Pair<Float, Float>?, seed: Int = frame): ByteArray {
        val random = Random(seed)
        val px = ByteArray(w * h)
        // Textured background: a vertical ramp, blocks of shelves and noise.
        val blocks = Random(7)
        val shade = IntArray(w * h) { 90 + 60 * (it / w) / h }
        repeat(40) {
            val bx = blocks.nextInt(w)
            val by = blocks.nextInt(h)
            val bw = 10 + blocks.nextInt(60)
            val bh = 10 + blocks.nextInt(60)
            val v = 40 + blocks.nextInt(180)
            for (y in by until minOf(h, by + bh)) for (x in bx until minOf(w, bx + bw)) shade[y * w + x] = v
        }
        fun disc(cx: Float, cy: Float) {
            for (y in 0 until h) for (x in 0 until w) {
                val d = hypot(x - cx, y - cy)
                when {
                    d < 10 -> shade[y * w + x] = 170 // hub
                    d < r - 4 -> shade[y * w + x] = 30
                    d < r -> shade[y * w + x] = 120 // lighter rim
                }
            }
        }
        disc(stackX, stackY)
        if (plateCenter != null) {
            val (cx, cy) = plateCenter
            disc(cx, cy)
            // The lifter's arm crosses the plate.
            for (y in (cy - 15).toInt() until (cy + 25).toInt()) for (x in (cx - r - 20).toInt() until (cx - 5).toInt()) {
                if (x in 0 until w && y in 0 until h) shade[y * w + x] = 150
            }
        }
        for (i in px.indices) px[i] = (shade[i] + random.nextInt(-12, 13)).coerceIn(0, 255).toByte()
        return px
    }

    /** Hint on standing frames: the shoulder, a bit below the plate center. */
    private fun hint(frame: Int): Pair<Float, Float>? =
        if (plateY(frame) == topY) (plateX + 10) / w to (topY + 0.9f * r) / h else null

    @Test
    fun followsSeededPlateNotTheStack() {
        val tracker = PlateTracker()
        var found = 0
        for (frame in 0 until 300) {
            val cy = plateY(frame)
            val obs = tracker.track(render(frame, plateX to cy), w, h, frame * 33L, hint(frame))
            if (frame < PlateTracker.FRAMES_TO_LOCK) continue
            assertNotNull("lost the plate at frame $frame", obs)
            obs!!
            found++
            assertEquals("x at frame $frame", plateX, obs.cx * w, 2.5f)
            assertEquals("y at frame $frame", cy, obs.cy * h, 2.5f)
            assertTrue("jumped to the stack at frame $frame", obs.cy * h < stackY - r)
        }
        // Either edge of the 4 px rim.
        assertEquals((r - 2) / w, tracker.lockedRadius!!, 3f / w)
        assertEquals(300 - PlateTracker.FRAMES_TO_LOCK, found)
    }

    @Test
    fun hiddenPlateIsAGapNotTheStack() {
        val tracker = PlateTracker()
        for (frame in 0 until 60) {
            val hidden = frame in 40..45
            val cy = plateY(frame)
            val obs = tracker.track(render(frame, if (hidden) null else plateX to cy), w, h, frame * 33L, hint(frame))
            if (hidden) assertNull("found something at frame $frame", obs)
            if (frame > 45) {
                assertNotNull("not reacquired at frame $frame", obs)
                assertEquals(cy, obs!!.cy * h, 2.5f)
            }
        }
    }

    @Test
    fun noSeedWithoutHint() {
        val tracker = PlateTracker()
        for (frame in 0 until 20) assertNull(tracker.track(render(frame, plateX to topY), w, h, frame * 33L, null))
        assertNull(tracker.lockedRadius)
    }

    @Test
    fun hintFarFromAnyPlateSeedsNothing() {
        val tracker = PlateTracker()
        val farHint = 0.9f to 0.1f
        for (frame in 0 until 20) assertNull(tracker.track(render(frame, plateX to topY), w, h, frame * 33L, farHint))
    }

    @Test
    fun resetForgetsTheLock() {
        val tracker = PlateTracker()
        for (frame in 0 until 15) tracker.track(render(frame, plateX to topY), w, h, frame * 33L, hint(frame))
        assertNotNull(tracker.lockedRadius)
        tracker.reset()
        assertNull(tracker.lockedRadius)
        assertNull(tracker.track(render(15, plateX to topY), w, h, 15 * 33L, null))
    }

    @Test
    fun shoulderHintOnlyForUprightPose() {
        fun pose(shoulderY: Float, hipY: Float, kneeY: Float, ankleY: Float): PoseResult {
            val lm = MutableList(33) { PoseKeypoint(0.5f, 0.5f, 0f, 1f) }
            for ((i, y) in listOf(
                PoseResult.LEFT_SHOULDER to shoulderY, PoseResult.RIGHT_SHOULDER to shoulderY,
                PoseResult.LEFT_HIP to hipY, PoseResult.RIGHT_HIP to hipY,
                PoseResult.LEFT_KNEE to kneeY, PoseResult.RIGHT_KNEE to kneeY,
                PoseResult.LEFT_ANKLE to ankleY, PoseResult.RIGHT_ANKLE to ankleY
            )) lm[i] = PoseKeypoint(0.4f, y, 0f, 1f)
            return PoseResult(lm, 0L, 720, 1280, true)
        }
        val upright = PlateTracker.shoulderHint(pose(0.3f, 0.5f, 0.7f, 0.9f))
        assertNotNull(upright)
        assertTrue(abs(upright!!.second - 0.3f) < 1e-6f)
        // Collapsed skeleton: "shoulders" below the hips.
        assertNull(PlateTracker.shoulderHint(pose(0.6f, 0.5f, 0.7f, 0.9f)))
    }
}
