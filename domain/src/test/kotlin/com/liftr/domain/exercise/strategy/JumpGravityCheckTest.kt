package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseThresholds
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.PoseResultTestFixtures
import com.liftr.domain.video.VideoSpeedCheck
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gravity fitted to a flight exposes timestamps stretched by slow motion read as real time. */
class JumpGravityCheckTest {

    private val userHeight = 1.75f
    private val calibration = CalibrationData(
        500f, userHeight, 0.45f, AnthropometricData.getDefaultRatios(userHeight, 75f)
    )
    private val strategy = JumpStrategy(ExerciseType.VERTICAL_JUMP, mockk<Logger>(relaxed = true))
    private val basePose = PoseResultTestFixtures.createIdealFrontalPose(1080, 1920, userHeight)
    private val normPerMeter = 0.85f / (ExerciseThresholds.Jump.NOSE_HEIGHT_RATIO * userHeight)
    private val g = 9.81f

    private fun pose(timeUs: Long, rise: Float, dip: Float): PoseResult {
        val landmarks = basePose.landmarks.mapIndexed { i, lm ->
            val upperBody = i <= PoseResult.RIGHT_HIP
            lm.copy(y = lm.y - rise * normPerMeter + (if (upperBody) dip * normPerMeter else 0f))
        }
        return basePose.copy(landmarks = landmarks, timestamp = timeUs / 1000, timestampUs = timeUs)
    }

    /** A countermovement jump filmed at [frameMs] spacing, its timestamps multiplied by [stretch]. */
    private fun fittedGravities(flightS: Float, frameMs: Long, stretch: Int): List<Float> {
        var t = 1_000L
        val frames = mutableListOf<PoseResult>()
        fun add(rise: Float = 0f, dip: Float = 0f) { frames += pose(t * 1000 * stretch, rise, dip); t += frameMs }
        val perS = (1000 / frameMs).toInt()
        repeat(perS / 2) { add() }
        val down = perS * 3 / 10; val up = perS / 5
        for (i in 1..down) add(dip = 0.20f * i / down)
        for (i in 1..up) add(dip = 0.20f * (1f - i.toFloat() / up))
        val t0 = t - frameMs / 2
        while (true) {
            val s = (t - t0) / 1000f
            if (s >= flightS) break
            add(rise = maxOf(0f, g * flightS / 2 * s - g * s * s / 2))
        }
        repeat(perS / 2) { add() }
        var state = ExercisePhaseState.initial()
        val gravities = mutableListOf<Float>()
        for (frame in frames) {
            val result = strategy.detectPhase(frame, calibration, state)
            state = result.newState
            result.flightGravity?.let { gravities += it }
        }
        return gravities
    }

    private fun warns(gravities: List<Float>, timeScale: Float) = VideoSpeedCheck.looksSlowMotion(gravities, timeScale)

    @Test
    fun `real-time flight fits gravity and does not warn`() {
        val fitted = fittedGravities(0.4f, 16, 1).single()
        assertEquals(9.81f, fitted, 0.5f)
        assertFalse(warns(listOf(fitted), 1f))
    }

    @Test
    fun `flight stretched by 2 warns when the time base said 1`() {
        // The jump itself is rejected (head rises a quarter of what the flight implies), the gravity still reports.
        val fitted = fittedGravities(0.45f, 16, 2).single()
        assertEquals(9.81f / 4, fitted, 0.2f)
        assertTrue(warns(listOf(fitted), 1f))
    }

    @Test
    fun `flight stretched by 4 warns when the time base said 1`() {
        // 2 s of stretched flight is discarded at 1 s: the rising half still gives the curvature.
        val fitted = fittedGravities(0.5f, 16, 4).single()
        assertEquals(9.81f / 16, fitted, 0.15f)
        assertTrue(warns(listOf(fitted), 1f))
    }

    @Test
    fun `stretch of 4 does not warn when the time base was already 4`() {
        val fitted = fittedGravities(0.5f, 16, 4).single()
        assertFalse(warns(listOf(fitted), 4f))
    }

    @Test
    fun `too few flight samples give no fitted gravity and no warning`() {
        // 0.45 s at 66 ms spacing: 7 airborne frames.
        val fitted = fittedGravities(0.45f, 66, 2)
        assertTrue(fitted.isEmpty())
        assertFalse(warns(fitted, 1f))
    }

    @Test
    fun `stretch factors within 10 percent warn, others do not`() {
        assertTrue(VideoSpeedCheck.looksSlowMotion(listOf(9.81f / 4 * 0.95f), 1f))  // stretch ~2.05
        assertTrue(VideoSpeedCheck.looksSlowMotion(listOf(9.81f / 64), 1f))         // stretch 8
        assertFalse(VideoSpeedCheck.looksSlowMotion(listOf(9.81f / 9), 1f))         // stretch 3
        assertFalse(VideoSpeedCheck.looksSlowMotion(listOf(9.0f), 1f))
    }
}
