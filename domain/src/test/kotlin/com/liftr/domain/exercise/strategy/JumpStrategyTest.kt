package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseThresholds
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.JumpData
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.PoseResultTestFixtures
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [JumpStrategy] with synthetic poses that follow real ballistic flight,
 * sampled at 30 fps, and checks rep counting and measured height.
 */
class JumpStrategyTest {

    private val userHeight = 1.75f
    private val calibration = CalibrationData(
        500f, userHeight, 0.45f, AnthropometricData.getDefaultRatios(userHeight, 75f)
    )
    private val strategy = JumpStrategy(ExerciseType.VERTICAL_JUMP, mockk<Logger>(relaxed = true))

    private val basePose = PoseResultTestFixtures.createIdealFrontalPose(1080, 1920, userHeight)

    // The fixture's nose-to-toe span is 0.85 of the frame; in meters it is the
    // nose height of a 1.75 m athlete.
    private val normPerMeter = 0.85f / (ExerciseThresholds.Jump.NOSE_HEIGHT_RATIO * userHeight)

    private val g = 9.81f
    private val frameMs = 33L

    /** A pose with the hips (and everything above) lowered by [dip] and the whole body raised by [rise], both in meters. */
    private fun pose(timestamp: Long, dip: Float = 0f, rise: Float = 0f): PoseResult {
        val landmarks = basePose.landmarks.mapIndexed { i, lm ->
            val upperBody = i <= PoseResult.RIGHT_HIP
            val y = lm.y - rise * normPerMeter + (if (upperBody) dip * normPerMeter else 0f)
            lm.copy(y = y)
        }
        return basePose.copy(landmarks = landmarks, timestamp = timestamp)
    }

    /**
     * Builds a frame sequence: stand, optional countermovement, ballistic flight of [flightS], land, stand.
     * [landingShift] (m) is how much higher the body appears after landing, e.g. a step farther from the
     * camera; it builds up linearly during the flight.
     */
    private fun jumpFrames(
        startMs: Long,
        flightS: Float,
        countermovement: Boolean = true,
        landingShift: Float = 0f,
        frameMs: Long = this.frameMs
    ): List<PoseResult> {
        val frames = mutableListOf<PoseResult>()
        var t = startMs
        fun add(dip: Float = 0f, rise: Float = 0f) { frames += pose(t, dip, rise); t += frameMs }
        val perSecond = 1000f / frameMs

        repeat((perSecond / 2).toInt()) { add() }              // stand 0.5 s
        if (countermovement) {
            val down = (perSecond * 0.3f).toInt(); val up = (perSecond * 0.2f).toInt()
            for (i in 1..down) add(dip = 0.20f * i / down)      // dip 20 cm
            for (i in 1..up) add(dip = 0.20f * (1f - i.toFloat() / up)) // push off
        }

        // Ballistic flight starting exactly between frames' timing, at t0.
        val t0 = t - frameMs / 2
        val v0 = g * flightS / 2f
        while (true) {
            val s = (t - t0) / 1000f
            if (s >= flightS) break
            add(rise = maxOf(0f, v0 * s - g * s * s / 2f) + landingShift * s / flightS)
        }
        repeat((perSecond / 2).toInt()) { add(rise = landingShift) } // land and stand
        return frames
    }

    private data class Run(val reps: Int, val jumps: List<JumpData>, val phases: Set<MovementPhase>)

    private fun run(frames: List<PoseResult>): Run {
        var state = ExercisePhaseState.initial()
        val jumps = mutableListOf<JumpData>()
        val phases = mutableSetOf<MovementPhase>()
        var reps = 0
        for (frame in frames) {
            val result = strategy.detectPhase(frame, calibration, state)
            state = result.newState
            phases += state.currentPhase
            if (result.repCompleted) {
                reps++
                jumps += result.jump!!
            }
        }
        return Run(reps, jumps, phases)
    }

    private fun expectedHeight(flightS: Float) = g * flightS * flightS / 8f

    @Test
    fun `countermovement jump counts one rep with accurate height`() {
        val run = run(jumpFrames(1000, flightS = 0.5f))

        assertEquals(1, run.reps)
        assertEquals(expectedHeight(0.5f), run.jumps.single().height.value, 0.01f)
    }

    @Test
    fun `same jump sampled at 30 and 120 fps gives the same rep, height and phases`() {
        val at30 = run(jumpFrames(1000, flightS = 0.5f, frameMs = 33))
        val at120 = JumpStrategy(ExerciseType.VERTICAL_JUMP, mockk<Logger>(relaxed = true)).let { fresh ->
            var state = ExercisePhaseState.initial()
            val jumps = mutableListOf<JumpData>()
            val phases = mutableSetOf<MovementPhase>()
            jumpFrames(1000, flightS = 0.5f, frameMs = 8).forEach { frame ->
                val result = fresh.detectPhase(frame, calibration, state)
                state = result.newState
                phases += state.currentPhase
                if (result.repCompleted) jumps += result.jump!!
            }
            Run(jumps.size, jumps, phases)
        }

        assertEquals(1, at30.reps)
        assertEquals(at30.reps, at120.reps)
        assertEquals(at30.phases, at120.phases)
        assertEquals(at30.jumps.single().height.value, at120.jumps.single().height.value, 0.01f)
    }

    @Test
    fun `countermovement jump passes through the shared phase cycle`() {
        val run = run(jumpFrames(1000, flightS = 0.5f))

        assertTrue(
            run.phases.containsAll(
                listOf(MovementPhase.ECCENTRIC, MovementPhase.BOTTOM, MovementPhase.CONCENTRIC, MovementPhase.TOP)
            )
        )
    }

    @Test
    fun `jump without countermovement is still counted`() {
        val run = run(jumpFrames(1000, flightS = 0.4f, countermovement = false))

        assertEquals(1, run.reps)
        assertEquals(expectedHeight(0.4f), run.jumps.single().height.value, 0.01f)
    }

    @Test
    fun `height stays within 1 cm whatever the frame phase at takeoff`() {
        for (offset in 0L until frameMs step 4) {
            val run = run(jumpFrames(1000 + offset, flightS = 0.45f))
            assertEquals("offset $offset ms", 1, run.reps)
            assertEquals("offset $offset ms", expectedHeight(0.45f), run.jumps.single().height.value, 0.01f)
        }
    }

    @Test
    fun `landing farther from the camera is still counted and measured`() {
        // The floor appears ~4 cm higher where the feet land: they never come
        // back to the takeoff level.
        val run = run(jumpFrames(1000, flightS = 0.5f, landingShift = 0.04f))

        assertEquals(1, run.reps)
        assertEquals(expectedHeight(0.5f), run.jumps.single().height.value, 0.015f)
    }

    @Test
    fun `jumps after landing elsewhere are measured from the new spot`() {
        val frames = jumpFrames(1000, flightS = 0.5f, landingShift = 0.04f) +
            jumpFrames(3000, flightS = 0.45f).map { frame ->
                // Second jump starts and ends at the shifted spot.
                frame.copy(landmarks = frame.landmarks.map { it.copy(y = it.y - 0.04f * normPerMeter) })
            }

        val run = run(frames)

        assertEquals(2, run.reps)
        assertEquals(expectedHeight(0.45f), run.jumps[1].height.value, 0.01f)
    }

    @Test
    fun `feet glitching up during the dip is not a jump`() {
        var t = 1000L
        val frames = buildList {
            repeat(15) { add(pose(t)); t += frameMs }
            for (i in 1..12) {
                val dip = 0.20f * i / 12
                // Frames 6-7: the feet (with the whole body) jump 6 cm while the hips keep sinking.
                add(pose(t, dip = dip, rise = if (i in 6..7) 0.06f else 0f)); t += frameMs
            }
            for (i in 1..12) { add(pose(t, dip = 0.20f * (1f - i / 12f))); t += frameMs }
            repeat(15) { add(pose(t)); t += frameMs }
        }

        assertEquals(0, run(frames).reps)
    }

    @Test
    fun `landing closer to the camera is measured to the lower floor`() {
        // Jumping toward the camera: the floor where the feet land appears
        // lower than the takeoff level, so they touch down below it.
        for (shift in listOf(0.04f, 0.10f)) {
            val run = run(jumpFrames(1000, flightS = 0.5f, landingShift = -shift))

            assertEquals("shift $shift m", 1, run.reps)
            assertEquals("shift $shift m", expectedHeight(0.5f), run.jumps.single().height.value, 0.015f)
        }
    }

    @Test
    fun `takeoff to apex height matches the flight on a clean jump whatever the frame phase`() {
        for (offset in 0L until frameMs step 4) {
            val apex = run(jumpFrames(1000 + offset, flightS = 0.5f)).jumps.single().apexHeight
            assertEquals("offset $offset ms", expectedHeight(0.5f), apex!!.value, 0.01f)
        }
    }

    @Test
    fun `one frame with the hips mistracked does not bend takeoff to apex`() {
        val frames = jumpFrames(1000, flightS = 0.5f).toMutableList()
        // Fourth airborne frame: stand 15 + countermovement 15 + 3.
        val glitched = frames[33]
        frames[33] = glitched.copy(landmarks = glitched.landmarks.mapIndexed { i, lm ->
            if (i == PoseResult.LEFT_HIP || i == PoseResult.RIGHT_HIP) lm.copy(y = lm.y + 0.10f * normPerMeter) else lm
        })

        val apex = run(frames).jumps.single().apexHeight

        assertEquals(expectedHeight(0.5f), apex!!.value, 0.01f)
    }

    @Test
    fun `one frame detected upside down mid-flight is skipped`() {
        val frames = jumpFrames(1000, flightS = 0.5f).toMutableList()
        // As seen on a real per-frame detection: head and feet swapped, hips in place.
        val flipped = frames[33]
        val feet = setOf(
            PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE, PoseResult.LEFT_HEEL, PoseResult.RIGHT_HEEL,
            PoseResult.LEFT_FOOT_INDEX, PoseResult.RIGHT_FOOT_INDEX
        )
        val noseY = flipped.landmarks[PoseResult.NOSE].y
        val toeY = flipped.landmarks[PoseResult.LEFT_FOOT_INDEX].y
        frames[33] = flipped.copy(landmarks = flipped.landmarks.mapIndexed { i, lm ->
            when {
                i == PoseResult.NOSE -> lm.copy(y = toeY)
                i in feet -> lm.copy(y = noseY)
                else -> lm
            }
        })

        val run = run(frames)

        assertEquals(1, run.reps)
        assertEquals(expectedHeight(0.5f), run.jumps.single().apexHeight!!.value, 0.01f)
        assertEquals(expectedHeight(0.5f), run.jumps.single().height.value, 0.01f)
    }

    @Test
    fun `folding the legs before landing stretches flight time but not takeoff to apex`() {
        // Seen on real side-on jumps: on the way down the feet are pulled up
        // [tuck] toward the body, so the body falls that much below its takeoff
        // level before the toes touch, and the flight time grows.
        val flightS = 0.5f
        val tuck = 0.08f
        val feet = setOf(
            PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE, PoseResult.LEFT_HEEL, PoseResult.RIGHT_HEEL,
            PoseResult.LEFT_FOOT_INDEX, PoseResult.RIGHT_FOOT_INDEX
        )
        val frames = mutableListOf<PoseResult>()
        var t = 1000L
        fun add(rise: Float, feetUp: Float = 0f) {
            val body = pose(t, rise = rise)
            frames += body.copy(landmarks = body.landmarks.mapIndexed { i, lm ->
                if (i in feet) lm.copy(y = lm.y - feetUp * normPerMeter) else lm
            })
            t += frameMs
        }
        repeat(15) { add(0f) }
        val t0 = t - frameMs / 2
        val v0 = g * flightS / 2f
        while (true) {
            val s = (t - t0) / 1000f
            val rise = v0 * s - g * s * s / 2f
            val descending = s > flightS / 2
            // Feet fold up through the descent; contact once they reach the floor.
            val feetUp = if (descending) tuck * ((s - flightS / 2) / (flightS / 2)).coerceAtMost(1f) else 0f
            if (rise + feetUp <= 0f) break
            add(rise, feetUp)
        }
        for (i in 1..10) add(-tuck * (1f - i / 10f))   // land in a crouch and stand up
        repeat(15) { add(0f) }

        val jump = run(frames).jumps.single()

        assertEquals(expectedHeight(flightS), jump.apexHeight!!.value, 0.015f)
        assertTrue("flight time should overestimate: ${jump.height.value}", jump.height.value > expectedHeight(flightS) + 0.02f)
    }

    /** [frames] standing frames with [edit] applied to the landmarks of frame i. */
    private fun standing(frames: Int, edit: (Int, List<com.liftr.domain.model.PoseKeypoint>) -> List<com.liftr.domain.model.PoseKeypoint>) =
        (0 until frames).map { i ->
            val p = pose(1000L + i * frameMs)
            p.copy(landmarks = edit(i, p.landmarks))
        }

    @Test
    fun `toes rising on their own while standing is not a jump`() {
        // Seen on a real recording: the foot rocks onto the heel (or the toe
        // landmark drifts) by 6 cm for ~0.8 s while the body stays put.
        val toes = setOf(PoseResult.LEFT_FOOT_INDEX, PoseResult.RIGHT_FOOT_INDEX)
        val frames = standing(60) { i, lms ->
            if (i in 25..48) lms.mapIndexed { idx, lm -> if (idx in toes) lm.copy(y = lm.y - 0.06f * normPerMeter) else lm }
            else lms
        }

        assertEquals(0, run(frames).reps)
    }

    @Test
    fun `far foot flickering in and out of view side-on is not a jump`() {
        // Side-on the far foot sits ~11 cm higher in the image, and its
        // visibility hovers around the threshold.
        val far = setOf(PoseResult.LEFT_FOOT_INDEX, PoseResult.LEFT_ANKLE)
        val frames = standing(90) { i, lms ->
            val shown = (i / 8) % 2 == 1
            lms.mapIndexed { idx, lm ->
                if (idx in far) lm.copy(y = lm.y - 0.11f * normPerMeter, visibility = if (shown) 0.4f else 0.2f) else lm
            }
        }

        assertEquals(0, run(frames).reps)
    }

    @Test
    fun `walking back to the takeoff spot after landing closer is not a jump`() {
        for (footLift in listOf(0.10f, 0.15f)) {
            // Jump toward the camera, landing 10 cm lower in the image.
            val frames = jumpFrames(1000, flightS = 0.5f, landingShift = -0.10f).toMutableList()
            var t = frames.last().timestamp + frameMs
            // Two steps back, one foot at a time: the swinging foot rises
            // [footLift] while the body climbs back to the takeoff level.
            val stepFrames = 14
            for (step in 0 until 2) {
                val feet = if (step == 0) {
                    setOf(PoseResult.LEFT_ANKLE, PoseResult.LEFT_HEEL, PoseResult.LEFT_FOOT_INDEX)
                } else {
                    setOf(PoseResult.RIGHT_ANKLE, PoseResult.RIGHT_HEEL, PoseResult.RIGHT_FOOT_INDEX)
                }
                for (i in 1..stepFrames) {
                    val progress = (step * stepFrames + i).toFloat() / (2 * stepFrames)
                    val body = pose(t, rise = -0.10f * (1f - progress))
                    val lift = footLift * kotlin.math.sin(Math.PI * i / stepFrames).toFloat() * normPerMeter
                    frames += body.copy(
                        landmarks = body.landmarks.mapIndexed { index, lm ->
                            if (index in feet) lm.copy(y = lm.y - lift) else lm
                        }
                    )
                    t += frameMs
                }
            }
            repeat(30) { frames += pose(t); t += frameMs }   // stand 1 s back at the takeoff spot

            val run = run(frames + jumpFrames(t, flightS = 0.45f))

            assertEquals("foot lift $footLift m", 2, run.reps)
            assertEquals("foot lift $footLift m", expectedHeight(0.45f), run.jumps[1].height.value, 0.01f)
        }
    }

    @Test
    fun `jumps after landing closer to the camera are measured from the new spot`() {
        // Landing closer shows the floor 4 cm lower: the feet settle below the takeoff level.
        val frames = jumpFrames(1000, flightS = 0.5f, landingShift = -0.04f) +
            jumpFrames(3000, flightS = 0.45f).map { frame ->
                frame.copy(landmarks = frame.landmarks.map { it.copy(y = it.y + 0.04f * normPerMeter) })
            }

        val run = run(frames)

        assertEquals(2, run.reps)
        assertEquals(expectedHeight(0.45f), run.jumps[1].height.value, 0.01f)
    }

    @Test
    fun `a first frame caught while walking into place does not block the jump`() {
        // The first 3 frames are 8 cm higher (still stepping into position).
        val walkIn = (0 until 3).map { i -> pose(500L + i * frameMs, rise = 0.08f) }

        val run = run(walkIn + jumpFrames(1000, flightS = 0.45f))

        assertEquals(1, run.reps)
        assertEquals(expectedHeight(0.45f), run.jumps.single().height.value, 0.01f)
    }

    @Test
    fun `consecutive jumps are each counted and measured`() {
        val flights = listOf(0.5f, 0.45f, 0.4f)
        val frames = mutableListOf<PoseResult>()
        var start = 1000L
        for (flight in flights) {
            val set = jumpFrames(start, flight)
            frames += set
            start = set.last().timestamp + frameMs
        }

        val run = run(frames)

        assertEquals(3, run.reps)
        run.jumps.zip(flights).forEach { (jump, flight) ->
            assertEquals(expectedHeight(flight), jump.height.value, 0.01f)
        }
    }

    @Test
    fun `landmark jitter of about 1 cm keeps height within 2 cm`() {
        val random = java.util.Random(42)
        val noisy = jumpFrames(1000, flightS = 0.5f).map { frame ->
            frame.copy(landmarks = frame.landmarks.map { lm ->
                lm.copy(y = lm.y + (random.nextGaussian() * 0.01 * normPerMeter).toFloat())
            })
        }

        val run = run(noisy)

        assertEquals(1, run.reps)
        assertEquals(expectedHeight(0.5f), run.jumps.single().height.value, 0.02f)
    }

    @Test
    fun `single-frame foot blip is not a jump`() {
        var t = 1000L
        val frames = buildList {
            repeat(15) { add(pose(t)); t += frameMs }
            add(pose(t, rise = 0.08f)); t += frameMs
            repeat(15) { add(pose(t)); t += frameMs }
        }

        assertEquals(0, run(frames).reps)
    }

    @Test
    fun `feet staying up longer than a real flight is discarded`() {
        var t = 1000L
        val frames = buildList {
            repeat(15) { add(pose(t)); t += frameMs }
            repeat(45) { add(pose(t, rise = 0.30f)); t += frameMs } // ~1.5 s, e.g. stepped onto a box
            repeat(15) { add(pose(t)); t += frameMs }
        }

        assertEquals(0, run(frames).reps)
    }

    @Test
    fun `squatting down and standing up without leaving the ground is not a jump`() {
        var t = 1000L
        val frames = buildList {
            repeat(15) { add(pose(t)); t += frameMs }
            for (i in 1..10) { add(pose(t, dip = 0.30f * i / 10)); t += frameMs }
            for (i in 1..10) { add(pose(t, dip = 0.30f * (1f - i / 10f))); t += frameMs }
            repeat(15) { add(pose(t)); t += frameMs }
        }

        val run = run(frames)

        assertEquals(0, run.reps)
    }

    private val approachStrategy = JumpStrategy(ExerciseType.APPROACH_JUMP, mockk<Logger>(relaxed = true))

    private fun runApproach(frames: List<PoseResult>): Run {
        var state = ExercisePhaseState.initial()
        val jumps = mutableListOf<JumpData>()
        for (frame in frames) {
            val result = approachStrategy.detectPhase(frame, calibration, state)
            state = result.newState
            if (result.repCompleted) jumps += result.jump!!
        }
        return Run(jumps.size, jumps, emptySet())
    }

    /**
     * A run-up seen side-on, flown in the air only: [strides] bounds of 0.18 s, each landing on a 0.2 s
     * stance, a last stance, a jump of [flightS], landing and standing. The athlete moves along the image
     * only in the air (3 m/s), toes stay put while planted. Image artifacts:
     * - [slope]: the floor rises this much per meter run (camera roll, a run off parallel);
     * - [shrink]: the body is this much smaller at the end of the run than at the start;
     * - [drift]: the floor creeps up the image at this many m/s;
     * - [landingShift]: the floor is this many meters higher on landing;
     * - [farToeUp]: the right toe is this many meters higher, being deeper in the scene;
     * - [oneFoot]: the right foot is driven up by 0.3 m during the last 0.25 s before the jump;
     * - [swapped]: time ranges (ms from the start) in which left and right landmarks are labeled the other way.
     */
    private fun approachFrames(
        flightS: Float,
        strides: Int = 3,
        slope: Float = 0f,
        shrink: Float = 0f,
        drift: Float = 0.03f,
        landingShift: Float = 0f,
        farToeUp: Float = 0f,
        oneFoot: Boolean = false,
        swapped: List<LongRange> = emptyList()
    ): List<PoseResult> {
        val strideS = 0.18f
        val stanceS = 0.2f
        val runSpeed = 3f
        // Flights as (start, duration, takeoff speed) in seconds.
        val flights = mutableListOf<Triple<Float, Float, Float>>()
        var clock = stanceS
        repeat(strides) {
            flights += Triple(clock, strideS, g * strideS / 2f)
            clock += strideS + stanceS
        }
        val jumpStart = clock
        flights += Triple(jumpStart, flightS, g * flightS / 2f)
        val endS = jumpStart + flightS + 0.5f
        val airTotal = flights.sumOf { it.second.toDouble() }.toFloat()
        val aspect = 1080f / 1920f
        val footBase = basePose.landmarks[PoseResult.LEFT_FOOT_INDEX].y

        val frames = mutableListOf<PoseResult>()
        var tMs = 0L
        while (tMs / 1000f < endS) {
            val t = tMs / 1000f
            var rise = 0f
            var aloft = 0f
            for ((start, duration, v0) in flights) {
                val f = (t - start).coerceIn(0f, duration)
                aloft += f
                if (t > start && t < start + duration) rise = v0 * f - g * f * f / 2f
            }
            val run = runSpeed * aloft * normPerMeter                    // distance run, height units
            val progress = aloft / airTotal
            val scale = 1f - shrink * progress
            val floor = slope * run +
                drift * minOf(t, jumpStart) * normPerMeter +
                landingShift * normPerMeter * ((t - jumpStart) / flightS).coerceIn(0f, 1f)
            val driveUp = if (oneFoot) 0.3f * ((t - (jumpStart - 0.25f)) / 0.25f).coerceIn(0f, 1f) else 0f
            var landmarks = basePose.landmarks.mapIndexed { i, lm ->
                val extra = when (i) {
                    PoseResult.RIGHT_FOOT_INDEX, PoseResult.RIGHT_ANKLE -> driveUp + farToeUp
                    else -> 0f
                }
                lm.copy(
                    x = lm.x + run / aspect,
                    y = footBase - floor - scale * ((footBase - lm.y) + (rise + extra) * normPerMeter)
                )
            }
            if (swapped.any { tMs in it }) {
                landmarks = landmarks.toMutableList().also {
                    for ((l, r) in listOf(
                        PoseResult.LEFT_FOOT_INDEX to PoseResult.RIGHT_FOOT_INDEX,
                        PoseResult.LEFT_ANKLE to PoseResult.RIGHT_ANKLE
                    )) { val tmp = it[l]; it[l] = it[r]; it[r] = tmp }
                }
            }
            frames += basePose.copy(landmarks = landmarks, timestamp = 1000 + tMs)
            tMs += frameMs
        }
        return frames
    }

    private fun assertOneJump(frames: List<PoseResult>, flightS: Float) {
        val run = runApproach(frames)
        assertEquals(1, run.reps)
        assertEquals(expectedHeight(flightS), run.jumps.single().height.value, 0.02f)
    }

    @Test
    fun `approach jump after running strides counts one rep with accurate height`() {
        assertOneJump(approachFrames(flightS = 0.6f), 0.6f)
    }

    @Test
    fun `approach jump from one foot counts one rep with accurate height`() {
        assertOneJump(approachFrames(flightS = 0.6f, oneFoot = true), 0.6f)
    }

    @Test
    fun `approach jump landing at another floor level is measured the same`() {
        assertOneJump(approachFrames(flightS = 0.6f, landingShift = 0.12f), 0.6f)
    }

    @Test
    fun `approach jump up a slanted floor counts one rep with accurate height`() {
        // 3 degrees of camera roll, running up the slope: the floor creeps up about 0.16 m/s.
        assertOneJump(approachFrames(flightS = 0.6f, slope = 0.052f), 0.6f)
        assertOneJump(approachFrames(flightS = 0.6f, slope = 0.052f, oneFoot = true), 0.6f)
    }

    @Test
    fun `approach jump run off parallel with the body shrinking counts one rep`() {
        assertOneJump(approachFrames(flightS = 0.6f, slope = 0.035f, shrink = 0.1f), 0.6f)
    }

    @Test
    fun `approach jump with the far toe higher in the image counts one rep`() {
        assertOneJump(approachFrames(flightS = 0.6f, farToeUp = 0.03f), 0.6f)
        assertOneJump(approachFrames(flightS = 0.6f, farToeUp = 0.03f, oneFoot = true), 0.6f)
    }

    @Test
    fun `approach jump with left and right labels swapping counts one rep`() {
        // Swapped over the second stance and, once, mid-flight (the jump starts at 1.3 s).
        val swapped = listOf(500L..900L, 1500L..1700L)
        assertOneJump(approachFrames(flightS = 0.6f, farToeUp = 0.03f, swapped = swapped), 0.6f)
        assertOneJump(approachFrames(flightS = 0.6f, farToeUp = 0.03f, oneFoot = true, swapped = swapped), 0.6f)
    }

    @Test
    fun `running strides alone are not approach jumps`() {
        assertEquals(0, runApproach(approachFrames(flightS = 0.18f)).reps)
        assertEquals(0, runApproach(approachFrames(flightS = 0.18f, slope = 0.052f)).reps)
        assertEquals(0, runApproach(approachFrames(flightS = 0.18f, slope = 0.052f, strides = 6)).reps)
    }

    @Test
    fun `vertical jump strategy still counts a short hop`() {
        // 0.3 s is below the approach minimum but a valid vertical jump.
        assertEquals(1, run(jumpFrames(1000, flightS = 0.3f)).reps)
        assertEquals(0, runApproach(jumpFrames(1000, flightS = 0.3f)).reps)
    }

    @Test
    fun `fromFlightTime matches projectile motion`() {
        val jump = JumpData.fromFlightTime(500f)

        assertEquals(0.3066f, jump.height.value, 0.001f)
        assertEquals(2.4525f, jump.takeoffVelocity.value, 0.001f)
    }

    @Test
    fun `fromFlightTime restores flight clipped by the timing level`() {
        // A 0.5 s flight timed 2.5 cm above the ground measures shorter.
        val v0 = g * 0.5f / 2f
        val level = 0.025f
        val clipped = 2f * kotlin.math.sqrt(v0 * v0 - 2f * g * level) / g

        val jump = JumpData.fromFlightTime(clipped * 1000f, timingLevel = level)

        assertEquals(500f, jump.flightTimeMs, 0.5f)
        assertEquals(expectedHeight(0.5f), jump.height.value, 0.001f)
    }
}
