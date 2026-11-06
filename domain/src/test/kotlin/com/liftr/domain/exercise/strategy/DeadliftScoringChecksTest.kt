package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.PhaseTransitionResult
import com.liftr.domain.exercise.RepQualityScorer
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.BodyPartPositions
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.vision.IPositionCalculator
import io.mockk.every
import io.mockk.mockk
import com.liftr.domain.usecase.AnalyzeRepUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Deadlift lockout scoring, angles from the side facing the camera, and the bar-drift check. */
class DeadliftScoringChecksTest {

    private lateinit var positionCalculator: IPositionCalculator
    private lateinit var strategy: DeadliftStrategy

    private val calibrationData = CalibrationData(
        pixelsPerMeter = 500f,
        userHeight = 1.75f,
        shoulderWidth = 0.45f,
        anthropometricData = AnthropometricData.getDefaultRatios(1.75f, 75f),
        timestamp = 0L
    )

    /** Hips Y in meters the next frame reports (smaller = higher). */
    private var hipsMeters = 0.80f

    @Before
    fun setup() {
        positionCalculator = mockk()
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 400f, kneesY = 500f,
            hipsX = 250f, anklesY = 600f, anklesX = 260f, shouldersX = 240f
        )
        // Hips Y follows the test; every other value: 2 m per image height.
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } answers {
            val value = firstArg<Float>()
            if (value == 400f) hipsMeters else value * 2f
        }
        strategy = DeadliftStrategy(positionCalculator, mockk<Logger>(relaxed = true))
    }

    private fun pose(build: MutableList<PoseKeypoint>.() -> Unit): PoseResult {
        val landmarks = MutableList(33) { PoseKeypoint(0.5f, 0.5f, 0f, 0.1f) }
        landmarks.build()
        return PoseResult(landmarks = landmarks, timestamp = 0L, imageWidth = 1080, imageHeight = 1920, isPortrait = true)
    }

    /** One side of a body filmed side-on, joints at the given x. */
    private fun MutableList<PoseKeypoint>.side(
        left: Boolean,
        visibility: Float,
        shoulderX: Float = 0.50f, hipX: Float = 0.50f, kneeX: Float = 0.50f, ankleX: Float = 0.50f,
        wristX: Float = 0.50f, toesX: Float = 0.56f
    ) {
        fun at(leftIndex: Int, x: Float, y: Float) {
            this[if (left) leftIndex else leftIndex + 1] = PoseKeypoint(x, y, 0f, visibility)
        }
        at(PoseResult.LEFT_SHOULDER, shoulderX, 0.25f)
        at(PoseResult.LEFT_WRIST, wristX, 0.50f)
        at(PoseResult.LEFT_HIP, hipX, 0.50f)
        at(PoseResult.LEFT_KNEE, kneeX, 0.70f)
        at(PoseResult.LEFT_ANKLE, ankleX, 0.90f)
        at(PoseResult.LEFT_FOOT_INDEX, toesX, 0.92f)
    }

    /** Sets up at the floor, pulls, and ends on [top]; returns the last transition. */
    private fun pullTo(top: PoseResult): PhaseTransitionResult {
        val setup = pose { side(left = true, visibility = 1f) }
        var state = ExercisePhaseState.initial()
        hipsMeters = 0.80f
        state = strategy.detectPhase(setup, calibrationData, state).newState
        hipsMeters = 0.72f
        state = strategy.detectPhase(setup, calibrationData, state).newState
        assertEquals(MovementPhase.CONCENTRIC, state.currentPhase)
        hipsMeters = 0.35f
        return strategy.detectPhase(top, calibrationData, state)
    }

    @Test
    fun `a measured lockout completes the rep and scores the full depth part`() {
        val result = pullTo(pose { side(left = true, visibility = 1f); side(left = false, visibility = 1f) })

        assertTrue(result.repCompleted)
        val lockout = result.depthValidation
        assertNotNull(lockout)
        lockout!!
        assertTrue(lockout.isValid)
        assertEquals(30, RepQualityScorer.scoreDepth(lockout))
    }

    @Test
    fun `filmed side-on, the angles come from the near side alone`() {
        // The far (right) side is hidden: before, needing both sides left the
        // angles unmeasured and the lockout was taken on height alone.
        val result = pullTo(pose { side(left = true, visibility = 0.95f); side(left = false, visibility = 0.2f) })

        assertTrue(result.repCompleted)
        assertEquals(1.0f, result.depthValidation!!.confidence)
    }

    @Test
    fun `bent knees on the near side are no lockout`() {
        val result = pullTo(pose {
            side(left = true, visibility = 0.95f, kneeX = 0.58f) // knee pushed well forward
            side(left = false, visibility = 0.2f)
        })

        assertFalse(result.repCompleted)
    }

    @Test
    fun `with no angle measurable the lockout scores less`() {
        val result = pullTo(pose { side(left = true, visibility = 0.2f); side(left = false, visibility = 0.2f) })

        assertTrue(result.repCompleted)
        assertEquals(20, RepQualityScorer.scoreDepth(result.depthValidation))
    }

    @Test
    fun `hands over mid-foot are no bar drift`() {
        // Mid-foot is halfway from ankle (0.50) to toes (0.56).
        val issues = strategy.validateForm(pose { side(left = true, visibility = 1f, wristX = 0.53f) }, calibrationData).issues

        assertNull(issues.find { it.kind == "bar_drift" })
    }

    @Test
    fun `hands well ahead of mid-foot are bar drift`() {
        val issues = strategy.validateForm(pose { side(left = true, visibility = 1f, wristX = 0.65f) /* 12% of width: about 13 cm */ }, calibrationData).issues

        assertNotNull(issues.find { it.kind == "bar_drift" })
    }

    @Test
    fun `a bent mid-pull frame is not reported as an incomplete lockout`() {
        val midPull = pose { side(left = true, visibility = 1f, shoulderX = 0.62f, kneeX = 0.56f, wristX = 0.53f) }

        val issues = strategy.validateForm(midPull, calibrationData).issues

        assertTrue(issues.none { it.message.contains("lockout", ignoreCase = true) })
    }

    /**
     * Frames of one concentric pull at [fps], the bar (near wrist) rising at 1 m/s
     * with the knees still bent, frozen for [stallMs] from 400 ms on. A smooth
     * pull ([stallMs] = 0) is a cosine ease that comes to rest at 1.2 s.
     */
    private fun pullFrames(fps: Int, stallMs: Int, smooth: Boolean = false): List<PoseResult> {
        val frameMs = 1000.0 / fps
        val durationMs = if (smooth) 1200 else 1000
        return (0..(durationMs / frameMs).toInt()).map { i ->
            val tMs = (i * frameMs).toLong()
            val moving = when {
                tMs < 400 -> tMs
                tMs < 400 + stallMs -> 400L
                else -> tMs - stallMs
            } / 1000.0
            // Image-height units; the mock converts at 2 m per unit.
            val wristY = if (smooth) 0.9 - 0.3 * (1 - Math.cos(Math.PI * tMs / 1200.0)) / 2
            else 0.9 - 0.5 * moving
            val base = pose { side(left = true, visibility = 1f, shoulderX = 0.62f, kneeX = 0.56f, wristX = 0.53f) }
            val landmarks = base.landmarks.toMutableList()
            val wrist = landmarks[PoseResult.LEFT_WRIST]
            landmarks[PoseResult.LEFT_WRIST] = wrist.copy(y = wristY.toFloat())
            base.copy(landmarks = landmarks, timestamp = tMs)
        }
    }

    private fun hitches(frames: List<PoseResult>) = strategy
        .validateRep(frames.map { AnalyzeRepUseCase.RepFrame(it, MovementPhase.CONCENTRIC) }, calibrationData)
        .filter { it.kind == "hitch" }

    @Test
    fun `a smooth pull is no hitch, even as the bar slows toward the top`() {
        assertTrue(hitches(pullFrames(fps = 30, stallMs = 0, smooth = true)).isEmpty())
        assertTrue(hitches(pullFrames(fps = 120, stallMs = 0, smooth = true)).isEmpty())
        assertTrue(hitches(pullFrames(fps = 30, stallMs = 0)).isEmpty())
    }

    @Test
    fun `a stall of 100 ms or more mid-pull is one hitch`() {
        assertEquals(1, hitches(pullFrames(fps = 30, stallMs = 150)).size)
    }

    @Test
    fun `the same stall at 120 fps is a hitch too`() {
        assertEquals(1, hitches(pullFrames(fps = 120, stallMs = 150)).size)
    }

    @Test
    fun `a stall of 60 ms is no hitch`() {
        assertTrue(hitches(pullFrames(fps = 30, stallMs = 60)).isEmpty())
        assertTrue(hitches(pullFrames(fps = 120, stallMs = 60)).isEmpty())
    }

    @Test
    fun `a stall at lockout is no hitch`() {
        val locked = pullFrames(fps = 30, stallMs = 150).map {
            val landmarks = it.landmarks.toMutableList()
            // Straight near side: lockout reached.
            landmarks[PoseResult.LEFT_SHOULDER] = landmarks[PoseResult.LEFT_SHOULDER].copy(x = 0.50f)
            landmarks[PoseResult.LEFT_KNEE] = landmarks[PoseResult.LEFT_KNEE].copy(x = 0.50f)
            it.copy(landmarks = landmarks)
        }
        assertTrue(hitches(locked).isEmpty())
    }

    @Test
    fun `a hitch is found again in the next rep`() {
        val frames = pullFrames(fps = 30, stallMs = 150)
        assertEquals(1, hitches(frames).size)
        assertEquals(1, hitches(frames).size)
    }

    @Test
    fun `validateForm alone never reports a hitch`() {
        val issues = pullFrames(fps = 30, stallMs = 150)
            .flatMap { strategy.validateForm(it, calibrationData, null, MovementPhase.CONCENTRIC).issues }
        assertTrue(issues.none { it.kind == "hitch" })
    }
}
