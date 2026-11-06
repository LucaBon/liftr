package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseStrategyFactory
import com.liftr.domain.exercise.RepQualityScorer
import com.liftr.domain.exercise.Severity
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.BodyPartPositions
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.usecase.AnalyzeRepUseCase
import com.liftr.domain.value.Kilograms
import com.liftr.domain.vision.IPositionCalculator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Side-on bench press: near-arm lockout, chest pause / touch-and-go timing and
 * the per-fault checks. The position calculator is an identity stub: pixel
 * values passed in are already meters.
 */
class BenchPressFormTest {

    private lateinit var strategy: BenchPressStrategy
    private var wristsY = 0.5f

    private val calibrationData = CalibrationData(
        pixelsPerMeter = 500f,
        userHeight = 1.75f,
        shoulderWidth = 0.45f,
        anthropometricData = AnthropometricData.getDefaultRatios(1.75f, 75f),
        timestamp = 0L
    )

    @Before
    fun setup() {
        val positionCalculator = mockk<IPositionCalculator>()
        every { positionCalculator.getBodyPartPositions(any()) } answers {
            BodyPartPositions(
                headY = 0.1f, shouldersY = 0.3f, hipsY = 0.6f, kneesY = null,
                wristsY = wristsY, wristsX = 0.5f, elbowsY = 0.4f
            )
        }
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } answers { firstArg() }
        strategy = BenchPressStrategy(positionCalculator, mockk(relaxed = true))
    }

    /**
     * Left arm is the near arm (visible); right arm is occluded. [elbowOffsetX]
     * bends the near elbow sideways (0 = collinear = 180 degrees).
     */
    private fun pose(
        elbowOffsetX: Float = 0f,
        wristX: Float = 0.5f,
        wristY: Float = 0.5f,
        timestampMs: Long = 0L,
        nearIsLeft: Boolean = true
    ): PoseResult {
        val landmarks = MutableList(33) { PoseKeypoint(0.5f, 0.5f, 0f, 0.1f) }
        val (shoulder, elbow, wrist) = if (nearIsLeft) {
            Triple(PoseResult.LEFT_SHOULDER, PoseResult.LEFT_ELBOW, PoseResult.LEFT_WRIST)
        } else {
            Triple(PoseResult.RIGHT_SHOULDER, PoseResult.RIGHT_ELBOW, PoseResult.RIGHT_WRIST)
        }
        landmarks[shoulder] = PoseKeypoint(0.5f, 0.3f, 0f, 0.95f)
        landmarks[elbow] = PoseKeypoint(0.5f + elbowOffsetX, 0.4f, 0f, 0.95f)
        landmarks[wrist] = PoseKeypoint(wristX, wristY, 0f, 0.95f)
        return PoseResult(
            landmarks = landmarks,
            timestamp = timestampMs,
            imageWidth = 1000,
            imageHeight = 1000
        )
    }

    private val lockoutPose get() = pose(elbowOffsetX = 0f)
    private val bentPose get() = pose(elbowOffsetX = 0.15f)

    private class RepOutcome(val frames: List<AnalyzeRepUseCase.RepFrame>, val completed: Boolean)

    /** Descend 0.5 -> 0.7 m in 1 s, hold [pauseMs], press back up in 1 s. */
    private fun simulateRep(pauseMs: Long, fps: Int): RepOutcome {
        val frameMs = 1000L / fps
        val ys = mutableListOf<Pair<Long, Float>>()
        var t = 0L
        repeat(3) { ys += t to 0.5f; t += frameMs }
        var ms = 0L
        while (ms < 1000L) { ys += t to 0.5f + 0.2f * ms / 1000f; t += frameMs; ms += frameMs }
        ms = 0L
        while (ms < pauseMs) { ys += t to 0.7f; t += frameMs; ms += frameMs }
        ms = 0L
        while (ms < 1000L) { ys += t to 0.7f - 0.2f * ms / 1000f; t += frameMs; ms += frameMs }
        repeat(3) { ys += t to 0.5f; t += frameMs }

        var state = ExercisePhaseState.initial()
        var completed = false
        val frames = mutableListOf<AnalyzeRepUseCase.RepFrame>()
        for ((time, y) in ys) {
            wristsY = y
            val frame = pose(
                elbowOffsetX = if (kotlin.math.abs(y - 0.5f) <= 0.05f) 0f else 0.15f,
                wristY = y,
                timestampMs = time
            )
            val result = strategy.detectPhase(frame, calibrationData, state)
            state = result.newState
            completed = completed || result.repCompleted
            frames += AnalyzeRepUseCase.RepFrame(frame, state.currentPhase)
        }
        return RepOutcome(frames, completed)
    }

    // --- lockout on the near arm -----------------------------------------

    @Test
    fun `lockout is detected from the near arm when the far arm is occluded`() {
        wristsY = 0.5f
        var state = ExercisePhaseState.initial()
        state = strategy.detectPhase(lockoutPose, calibrationData, state).newState
        state = state.copy(
            currentPhase = MovementPhase.CONCENTRIC,
            startingWristsY = 0.5f,
            lowestWristsY = 0.7f
        )

        val result = strategy.detectPhase(lockoutPose, calibrationData, state)

        assertTrue(result.repCompleted)
        assertEquals(MovementPhase.TOP, result.newState.currentPhase)
    }

    @Test
    fun `lockout is detected from the right arm when that is the near side`() {
        wristsY = 0.5f
        val state = ExercisePhaseState.initial().copy(
            currentPhase = MovementPhase.CONCENTRIC,
            startingWristsY = 0.5f,
            lowestWristsY = 0.7f
        )

        assertTrue(strategy.detectPhase(pose(nearIsLeft = false), calibrationData, state).repCompleted)
    }

    @Test
    fun `a bent near arm does not complete the rep`() {
        wristsY = 0.5f
        val state = ExercisePhaseState.initial().copy(
            currentPhase = MovementPhase.CONCENTRIC,
            startingWristsY = 0.5f,
            lowestWristsY = 0.7f
        )

        assertTrue(!strategy.detectPhase(bentPose, calibrationData, state).repCompleted)
    }

    // --- chest pause / touch-and-go --------------------------------------

    private fun repIssues(rep: RepOutcome) = strategy.validateRep(rep.frames, calibrationData, null)

    @Test
    fun `a one second pause at the chest is measured and is not touch-and-go`() {
        for (fps in listOf(30, 120)) {
            val rep = simulateRep(pauseMs = 1000L, fps = fps)
            assertTrue("rep completes at $fps fps", rep.completed)
            val issues = repIssues(rep)

            assertTrue("no touch-and-go at $fps fps", issues.none { it.kind == "touch_and_go" })
            val pause = issues.single { it.kind == "chest_pause" }
            val ms = Regex("(\\d+) ms").find(pause.message)!!.groupValues[1].toFloat()
            assertEquals("pause at $fps fps", 1000f, ms, 150f)
        }
    }

    @Test
    fun `a touch-and-go rep is flagged`() {
        for (fps in listOf(30, 120)) {
            val rep = simulateRep(pauseMs = 0L, fps = fps)
            assertTrue("rep completes at $fps fps", rep.completed)

            val flagged = repIssues(rep).filter { it.kind == "touch_and_go" }

            assertEquals("at $fps fps", 1, flagged.size)
            assertEquals(Severity.INFO, flagged.single().severity)
        }
    }

    @Test
    fun `measuring the chest pause costs no form points`() {
        for (pauseMs in listOf(1000L, 0L)) {
            val issues = repIssues(simulateRep(pauseMs = pauseMs, fps = 30))

            assertTrue(issues.isNotEmpty())
            assertEquals("pause $pauseMs ms", 30, RepQualityScorer.scoreForm(issues))
        }
    }

    @Test
    fun `a rep without a descent reports no chest pause`() {
        val frames = List(6) { AnalyzeRepUseCase.RepFrame(pose(timestampMs = it * 33L), MovementPhase.TOP) }

        assertTrue(strategy.validateRep(frames, calibrationData, null).isEmpty())
    }

    // --- one test per fault, each firing once over the rep ---------------

    private fun analyse(
        poses: List<PoseResult>,
        state: ExercisePhaseState?,
        phase: MovementPhase = MovementPhase.TOP
    ): List<com.liftr.domain.exercise.FormIssue> =
        runBlocking {
            val factory = mockk<ExerciseStrategyFactory> { every { getStrategy(any()) } returns strategy }
            AnalyzeRepUseCase(factory, mockk(relaxed = true))(
                AnalyzeRepUseCase.Params(
                    exerciseType = ExerciseType.BENCH_PRESS,
                    calibrationData = calibrationData,
                    weight = Kilograms(60f),
                    bufferedFrames = poses.map { AnalyzeRepUseCase.RepFrame(it, phase) },
                    finalVelocityData = null,
                    phaseState = state
                )
            ).formValidation.issues
        }

    private val topState get() = ExercisePhaseState.initial().copy(startingWristsY = 0.5f)

    @Test
    fun `bar drift fires once over the rep`() {
        wristsY = 0.5f
        val poses = List(6) { pose(wristX = 0.8f) }

        val drift = analyse(poses, topState).filter { it.kind == "bar_drift" }

        assertEquals(1, drift.size)
        assertEquals(Severity.WARNING, drift.single().severity)
    }

    @Test
    fun `incomplete lockout fires once over the rep`() {
        wristsY = 0.5f
        val poses = List(6) { bentPose }

        val lockout = analyse(poses, topState).filter { it.kind == "incomplete_lockout" }

        assertEquals(1, lockout.size)
    }

    @Test
    fun `bent arms mid-rep are not an incomplete lockout`() {
        wristsY = 0.65f
        val poses = List(6) { bentPose }

        assertTrue(analyse(poses, topState).none { it.kind == "incomplete_lockout" })
    }

    @Test
    fun `bent arms in the concentric phase near the top are not an incomplete lockout`() {
        wristsY = 0.5f

        assertTrue(
            analyse(List(6) { bentPose }, topState, MovementPhase.CONCENTRIC)
                .none { it.kind == "incomplete_lockout" }
        )
    }

    @Test
    fun `a good paused rep has no faults`() {
        val rep = simulateRep(pauseMs = 1000L, fps = 30)
        wristsY = 0.5f

        val issues = (analyse(List(6) { lockoutPose }, topState) + repIssues(rep)).filter { it.kind != "chest_pause" }

        assertTrue("unexpected issues: $issues", issues.isEmpty())
    }
}
