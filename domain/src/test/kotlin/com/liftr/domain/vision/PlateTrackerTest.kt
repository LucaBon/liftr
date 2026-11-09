package com.liftr.domain.vision

import com.liftr.domain.model.ExerciseType
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

    // --- Bench (landscape) and deadlift (portrait): hint at the wrists, radius from the body ---

    /** Textured frame with one disc per (cx, cy, radius), like the squat renderer but any size. */
    private fun renderDiscs(fw: Int, fh: Int, frame: Int, discs: List<Triple<Float, Float, Float>>): ByteArray {
        val random = Random(frame)
        val blocks = Random(11)
        val shade = IntArray(fw * fh) { 90 + 60 * (it / fw) / fh }
        repeat(30) {
            val bx = blocks.nextInt(fw)
            val by = blocks.nextInt(fh)
            val bw = 10 + blocks.nextInt(50)
            val bh = 10 + blocks.nextInt(50)
            val v = 40 + blocks.nextInt(180)
            for (y in by until minOf(fh, by + bh)) for (x in bx until minOf(fw, bx + bw)) shade[y * fw + x] = v
        }
        for ((cx, cy, pr) in discs) for (y in 0 until fh) for (x in 0 until fw) {
            val d = hypot(x - cx, y - cy)
            when {
                d < 6 -> shade[y * fw + x] = 170
                d < pr - 4 -> shade[y * fw + x] = 30
                d < pr -> shade[y * fw + x] = 120
            }
        }
        return ByteArray(fw * fh) { (shade[it] + random.nextInt(-12, 13)).coerceIn(0, 255).toByte() }
    }

    @Test
    fun landscapeBenchPlateAtTheWristsLocksAndTracks() {
        val fw = 640
        val fh = 360
        val pr = 0.08f * fw
        val expected = pr / fw
        val tracker = PlateTracker()
        // 35 cm of a 45 cm plate, two reps at 30 fps.
        val travel = 0.35f / 0.45f * 2 * pr
        for (frame in 0 until 150) {
            val t = (frame % 75) / 75f
            val cy = 120f + travel * (1 - cos(2 * PI * t).toFloat()) / 2
            val wrist = (320f + 12) / fw to (cy + 0.4f * pr) / fh
            val obs = tracker.track(
                renderDiscs(fw, fh, frame, listOf(Triple(320f, cy, pr))), fw, fh, frame * 33L,
                wrist, hintAtPlate = true, expectedRadius = expected
            )
            if (frame < PlateTracker.FRAMES_TO_LOCK) continue
            assertNotNull("lost the plate at frame $frame", obs)
            assertEquals("x at frame $frame", 320f, obs!!.cx * fw, 2.5f)
            assertEquals("y at frame $frame", cy, obs.cy * fh, 2.5f)
        }
        assertEquals((pr - 2) / fw, tracker.lockedRadius!!, 3f / fw)
    }

    @Test
    fun portraitDeadliftPlateNearTheBottomIsFound() {
        val fw = 360
        val fh = 640
        val pr = 30f
        val tracker = PlateTracker()
        val cx = 200f
        val cy = 0.85f * fh
        var last: PlateObservation? = null
        for (frame in 0 until 15) {
            last = tracker.track(
                renderDiscs(fw, fh, frame, listOf(Triple(cx, cy, pr))), fw, fh, frame * 33L,
                (cx - 10) / fw to (cy - 0.5f * pr) / fh, hintAtPlate = true, expectedRadius = pr / fw
            )
        }
        assertNotNull(tracker.lockedRadius)
        assertEquals(cx, last!!.cx * fw, 2.5f)
        assertEquals(cy, last.cy * fh, 2.5f)
    }

    @Test
    fun tapSeedFindsThePlateWithoutAPose() {
        val fw = 640
        val fh = 360
        val pr = 0.08f * fw
        val tracker = PlateTracker()
        var last: PlateObservation? = null
        for (frame in 0 until 15) {
            // The tap lands about a radius off center.
            last = tracker.track(
                renderDiscs(fw, fh, frame, listOf(Triple(300f, 150f, pr))), fw, fh, frame * 33L,
                hint = null, expectedRadius = null, seed = (300f + 0.5f * pr) / fw to (150f + 0.9f * pr) / fh
            )
        }
        // Without an expected radius the 8% plate is below MIN_RADIUS; with one the tap finds it.
        assertNull("fallback range must not see an 8% plate", last)
        for (frame in 15 until 30) {
            last = tracker.track(
                renderDiscs(fw, fh, frame, listOf(Triple(300f, 150f, pr))), fw, fh, frame * 33L,
                hint = null, expectedRadius = pr / fw, seed = (300f + 0.5f * pr) / fw to (150f + 0.9f * pr) / fh
            )
        }
        assertNotNull(tracker.lockedRadius)
        assertEquals(300f, last!!.cx * fw, 2.5f)
        assertEquals(150f, last.cy * fh, 2.5f)
    }

    @Test
    fun tapWindowIsWiderThanTheWristWindow() {
        val fw = 640
        val fh = 360
        val pr = 0.08f * fw
        val frameOf = { f: Int -> renderDiscs(fw, fh, f, listOf(Triple(300f, 150f, pr))) }
        val off = 1.5f * pr // beyond the wrist window (1.2), inside the tap window (1.8)
        val wrist = PlateTracker()
        val tap = PlateTracker()
        repeat(PlateTracker.FRAMES_TO_LOCK + 2) { f ->
            wrist.track(frameOf(f), fw, fh, f * 33L, (300f + off) / fw to 150f / fh, true, pr / fw)
            tap.track(frameOf(f), fw, fh, f * 33L, null, false, pr / fw, (300f + off) / fw to 150f / fh)
        }
        assertNull(wrist.lockedRadius)
        assertNotNull(tap.lockedRadius)
    }

    @Test
    fun expectedRadiusIgnoresADecoyOfAnotherSize() {
        val fw = 640
        val fh = 360
        val pr = 0.08f * fw
        val tracker = PlateTracker()
        var last: PlateObservation? = null
        for (frame in 0 until 15) {
            // A big circle (2.2x) encloses the hint; the real plate sits slightly off it.
            val discs = listOf(Triple(380f, 200f, 2.2f * pr), Triple(300f, 150f, pr))
            last = tracker.track(
                renderDiscs(fw, fh, frame, discs), fw, fh, frame * 33L,
                300f / fw to 150f / fh, hintAtPlate = true, expectedRadius = pr / fw
            )
        }
        assertNotNull(tracker.lockedRadius)
        assertEquals((pr - 2) / fw, tracker.lockedRadius!!, 3f / fw)
        assertEquals(300f, last!!.cx * fw, 2.5f)
    }

    @Test
    fun wristHintUsesVisibleWristsAndBarHintPicksPerLift() {
        fun pose(left: PoseKeypoint, right: PoseKeypoint): PoseResult {
            val lm = MutableList(33) { PoseKeypoint(0.5f, 0.5f, 0f, 1f) }
            lm[PoseResult.LEFT_WRIST] = left
            lm[PoseResult.RIGHT_WRIST] = right
            return PoseResult(lm, 0L, 1280, 720, false)
        }
        val both = pose(PoseKeypoint(0.2f, 0.4f, 0f, 1f), PoseKeypoint(0.4f, 0.6f, 0f, 1f))
        val hint = PlateTracker.barHint(both, ExerciseType.BENCH_PRESS)!!
        assertEquals(0.3f, hint.first, 1e-6f)
        assertEquals(0.5f, hint.second, 1e-6f)
        val one = pose(PoseKeypoint(0.2f, 0.4f, 0f, 0.1f), PoseKeypoint(0.4f, 0.6f, 0f, 1f))
        assertEquals(0.4f, PlateTracker.barHint(one, ExerciseType.DEADLIFT)!!.first, 1e-6f)
        assertNull(PlateTracker.barHint(pose(PoseKeypoint(0f, 0f, 0f, 0f), PoseKeypoint(0f, 0f, 0f, 0f)), ExerciseType.DEADLIFT))
        // Squats keep the shoulder hint: this pose is not upright, so none.
        assertNull(PlateTracker.barHint(both, ExerciseType.BACK_SQUAT))
        assertNull(PlateTracker.barHint(both, ExerciseType.CLEAN))
    }
}
