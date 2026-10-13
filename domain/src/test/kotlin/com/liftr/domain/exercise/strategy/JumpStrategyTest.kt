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

    /** Builds a frame sequence: stand, optional countermovement, ballistic flight of [flightS], land, stand. */
    private fun jumpFrames(
        startMs: Long,
        flightS: Float,
        countermovement: Boolean = true
    ): List<PoseResult> {
        val frames = mutableListOf<PoseResult>()
        var t = startMs
        fun add(dip: Float = 0f, rise: Float = 0f) { frames += pose(t, dip, rise); t += frameMs }

        repeat(15) { add() }                                   // stand 0.5 s
        if (countermovement) {
            val down = 9; val up = 6
            for (i in 1..down) add(dip = 0.20f * i / down)      // dip 20 cm
            for (i in 1..up) add(dip = 0.20f * (1f - i.toFloat() / up)) // push off
        }

        // Ballistic flight starting exactly between frames' timing, at t0.
        val t0 = t - frameMs / 2
        val v0 = g * flightS / 2f
        while (true) {
            val s = (t - t0) / 1000f
            if (s >= flightS) break
            add(rise = maxOf(0f, v0 * s - g * s * s / 2f))
        }
        repeat(15) { add() }                                   // land and stand
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
