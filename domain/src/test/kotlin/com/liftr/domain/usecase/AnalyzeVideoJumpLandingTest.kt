package com.liftr.domain.usecase

import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseStrategy
import com.liftr.domain.exercise.ExerciseStrategyFactory
import com.liftr.domain.exercise.PhaseTransitionResult
import com.liftr.domain.exercise.strategy.JumpStrategy
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.JumpData
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.PoseResultTestFixtures
import com.liftr.domain.model.SetSummary
import com.liftr.domain.value.Kilograms
import com.liftr.domain.vision.VideoPoseSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The video path hands a jump's flight and landing frames to the rep's form
 * check, so a stiff landing is found; other lifts keep their rep buffer.
 */
class AnalyzeVideoJumpLandingTest {

    private val userHeight = 1.75f
    private val logger = mockk<Logger>(relaxed = true)
    private val basePose = PoseResultTestFixtures.createIdealFrontalPose(1080, 1920, userHeight)

    private fun calibration(bodyMass: Float?) = CalibrationData(
        500f, userHeight, 0.45f, AnthropometricData.getDefaultRatios(userHeight, bodyMass)
    )

    /** Side-on pose with the near (left) knee flexed by [flexionDeg]; the far leg hidden. */
    private fun legPose(timeMs: Long, flexionDeg: Double): PoseResult {
        val sx = basePose.aspectScaleX
        val half = Math.toRadians(flexionDeg / 2)
        val segment = 0.2f
        val ankleX = 0.5f; val ankleY = 0.9f
        val kneeX = ankleX + (segment * kotlin.math.sin(half)).toFloat() / sx
        val kneeY = ankleY - (segment * kotlin.math.cos(half)).toFloat()
        val hipX = kneeX - (segment * kotlin.math.sin(half)).toFloat() / sx
        val hipY = kneeY - (segment * kotlin.math.cos(half)).toFloat()
        val landmarks = basePose.landmarks.mapIndexed { i, lm ->
            when (i) {
                PoseResult.LEFT_HIP -> lm.copy(x = hipX, y = hipY)
                PoseResult.LEFT_KNEE -> lm.copy(x = kneeX, y = kneeY)
                PoseResult.LEFT_ANKLE -> lm.copy(x = ankleX, y = ankleY)
                PoseResult.RIGHT_HIP, PoseResult.RIGHT_KNEE, PoseResult.RIGHT_ANKLE -> lm.copy(visibility = 0.1f)
                else -> lm
            }
        }
        return basePose.copy(landmarks = landmarks, timestamp = timeMs, timestampUs = timeMs * 1000)
    }

    private val frameMs = 20L
    private val landingFrame = 45

    /**
     * One jump at 50 fps: stand, dip, drive, 400 ms of flight, land on frame
     * [landingFrame], bend the knees to [peakFlexionDeg] over 150 ms (phases
     * through a landing crouch), then stand. The video ends [endAfterLandingMs]
     * after the landing.
     */
    private fun video(peakFlexionDeg: Double, endAfterLandingMs: Long = 1000): List<PoseResult> {
        val last = landingFrame + (endAfterLandingMs / frameMs).toInt()
        return (0..last).map { i ->
            val sinceLandingMs = (i - landingFrame) * frameMs.toDouble()
            val flexion = when {
                i < 10 -> 5.0
                i < 25 -> 40.0 // the dip before takeoff: bent, but not a landing
                i < landingFrame -> 8.0
                sinceLandingMs <= 150 -> 8.0 + (peakFlexionDeg - 8.0) * sinceLandingMs / 150
                sinceLandingMs <= 300 -> peakFlexionDeg + (10.0 - peakFlexionDeg) * (sinceLandingMs - 150) / 150
                else -> 10.0
            }
            legPose(i * frameMs, flexion)
        }
    }

    private fun phaseAt(i: Int): MovementPhase = when {
        i < 10 -> MovementPhase.IDLE
        i < 20 -> MovementPhase.ECCENTRIC
        i < 25 -> MovementPhase.CONCENTRIC
        i < landingFrame -> MovementPhase.TOP
        i == landingFrame -> MovementPhase.IDLE
        i < landingFrame + 5 -> MovementPhase.ECCENTRIC // landing crouch
        i < landingFrame + 8 -> MovementPhase.BOTTOM
        i < landingFrame + 12 -> MovementPhase.CONCENTRIC
        else -> MovementPhase.IDLE
    }

    private val flight = JumpData.fromFlightTime(400f)

    private fun scriptedTransition(pose: PoseResult, phase: (Int) -> MovementPhase = ::phaseAt): PhaseTransitionResult {
        val i = (pose.timestamp / frameMs).toInt()
        return PhaseTransitionResult(
            ExercisePhaseState(currentPhase = phase(i)),
            repCompleted = i == landingFrame,
            jump = if (i == landingFrame) flight else null
        )
    }

    /** The real jump form checks, with the phases scripted by timestamp. */
    private fun jumpStrategy(phase: (Int) -> MovementPhase = ::phaseAt): ExerciseStrategy {
        val real = JumpStrategy(ExerciseType.VERTICAL_JUMP, logger)
        return object : ExerciseStrategy by real {
            override fun detectPhase(
                poseResult: PoseResult,
                calibrationData: CalibrationData,
                currentState: ExercisePhaseState
            ) = scriptedTransition(poseResult, phase)
        }
    }

    private fun analyze(
        poses: List<PoseResult>,
        strategy: ExerciseStrategy,
        type: ExerciseType = ExerciseType.VERTICAL_JUMP,
        calibration: CalibrationData = calibration(75f)
    ): SetSummary = runBlocking {
        val factory = mockk<ExerciseStrategyFactory> { every { getStrategy(any()) } returns strategy }
        val useCase = AnalyzeVideoUseCase(
            mockk(relaxed = true), factory, AnalyzeRepUseCase(factory, logger), mockk(relaxed = true), logger
        )
        val source = object : VideoPoseSource {
            override val totalFrames = poses.size
            override fun poses(include: (Long) -> Boolean): Flow<VideoPoseSource.Frame> =
                flow { poses.forEach { emit(VideoPoseSource.Frame(it.timestamp, it)) } }
            override fun close() = Unit
        }
        useCase.invoke(
            AnalyzeVideoUseCase.Params(
                videoFile = File("jump.mp4"),
                exerciseType = type,
                weight = Kilograms(0f),
                targetReps = 1,
                calibrationData = calibration
            ),
            source,
            onProgress = {}
        ).getOrThrow()
    }

    private fun SetSummary.stiffLanding(rep: Int = 0) =
        repScores[rep].formIssues.any { it.kind == "stiff_landing" }

    @Test
    fun `stiff landing is flagged on the jump`() {
        val summary = analyze(video(peakFlexionDeg = 15.0), jumpStrategy())

        assertEquals(1, summary.repScores.size)
        assertTrue(summary.stiffLanding())
        assertTrue(summary.aggregateFormIssues.any { it.issue.kind == "stiff_landing" })
    }

    @Test
    fun `soft landing is no fault`() {
        val summary = analyze(video(peakFlexionDeg = 70.0), jumpStrategy())

        assertEquals(1, summary.repScores.size)
        assertFalse(summary.stiffLanding())
    }

    @Test
    fun `video ending right after the last landing still checks it`() {
        val stiff = analyze(video(peakFlexionDeg = 15.0, endAfterLandingMs = 160), jumpStrategy())
        val soft = analyze(video(peakFlexionDeg = 70.0, endAfterLandingMs = 160), jumpStrategy())

        assertTrue(stiff.stiffLanding())
        assertFalse(soft.stiffLanding())
    }

    @Test
    fun `non-jump rep buffer keeps only the eccentric to concentric frames`() {
        val buffered = mutableListOf<List<AnalyzeRepUseCase.RepFrame>>()
        // Same scripted phases, rep counted at the first TOP frame like a lift's lockout.
        // A lift whose phases come from its strategy (a squat's come from its bar track).
        val strategy = mockk<ExerciseStrategy>(relaxed = true) {
            every { exerciseType } returns ExerciseType.CLEAN
            every { detectPhase(any(), any(), any()) } answers {
                val i = (firstArg<PoseResult>().timestamp / frameMs).toInt()
                PhaseTransitionResult(ExercisePhaseState(currentPhase = phaseAt(i)), repCompleted = i == 25)
            }
            every { validateRep(capture(buffered), any(), any()) } returns emptyList()
        }

        val summary = analyze(video(15.0), strategy, ExerciseType.CLEAN)

        assertEquals(1, summary.repScores.size)
        assertEquals(1, buffered.size)
        assertEquals(15, buffered[0].size)
        assertTrue(buffered[0].none { it.phase == MovementPhase.TOP || it.phase == MovementPhase.IDLE })
        assertTrue(summary.repPeakPowers.isEmpty())
    }

    @Test
    fun `another flight within the landing window does not replace the judged landing`() {
        // A stiff landing, then 60 ms of contact and a short hop (a glitch or a
        // quick rebound) that lands with deep knees, all within 300 ms.
        val rebound = landingFrame + 3..landingFrame + 6
        val poses = video(peakFlexionDeg = 15.0).mapIndexed { i, pose ->
            if (i > rebound.last) legPose(i * frameMs, 80.0) else pose
        }
        val phases = { i: Int ->
            when {
                i in rebound -> MovementPhase.TOP
                i > rebound.last && i < landingFrame + 20 -> MovementPhase.IDLE
                else -> phaseAt(i)
            }
        }

        val summary = analyze(poses, jumpStrategy(phases))

        assertEquals(1, summary.repScores.size)
        assertTrue(summary.stiffLanding())
    }
}
