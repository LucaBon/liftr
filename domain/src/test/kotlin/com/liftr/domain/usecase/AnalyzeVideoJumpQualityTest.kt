package com.liftr.domain.usecase

import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseStrategy
import com.liftr.domain.exercise.ExerciseStrategyFactory
import com.liftr.domain.exercise.JumpPathWindow
import com.liftr.domain.exercise.PhaseTransitionResult
import com.liftr.domain.exercise.strategy.JumpStrategy
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.JumpData
import com.liftr.domain.model.JumpQuality
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The video path carries each jump's free-fall check into the summary as a
 * [JumpQuality], aligned with its height, so best and average can leave out
 * the badly tracked ones.
 */
class AnalyzeVideoJumpQualityTest {

    private val userHeight = 1.75f
    private val logger = mockk<Logger>(relaxed = true)
    private val basePose = PoseResultTestFixtures.createIdealFrontalPose(1080, 1920, userHeight)
    private val cmPerUnitY = JumpPathWindow.cmPerUnitY(basePose, userHeight)!!
    private val frameMs = 17L
    private val standMs = 1000L
    private val v0 = 2.5

    /** One jump in the video: when it takes off, and the gravity its tracked flight follows. */
    private class Flight(val takeoffMs: Long, val gravity: Double) {
        val flightMs: Long = (2 * 2.5 / gravity * 1000).toLong()
        val landingMs: Long get() = takeoffMs + flightMs
    }

    /**
     * Jumps one after another, each after [standMs] of standing still. A
     * flight tracked under any gravity but 9.81 is what a lost joint or a
     * wrong scale looks like to the fit check.
     */
    private fun flights(vararg gravities: Double): List<Flight> {
        var takeoff = standMs
        return gravities.map { g -> Flight(takeoff, g).also { takeoff = it.landingMs + frameMs + standMs } }
    }

    /** Whole-body pose raised by [riseM] meters. */
    private fun poseAt(timeMs: Long, riseM: Double): PoseResult {
        val dy = (riseM * 100 / cmPerUnitY).toFloat()
        return basePose.copy(
            landmarks = basePose.landmarks.map { it.copy(y = it.y - dy) },
            timestamp = timeMs,
            timestampUs = timeMs * 1000
        )
    }

    private fun video(flights: List<Flight>): List<PoseResult> {
        val end = flights.last().landingMs + standMs
        return (0..end step frameMs).map { t ->
            val flight = flights.firstOrNull { t in it.takeoffMs..it.landingMs }
            val rise = flight?.let {
                val tau = (t - it.takeoffMs) / 1000.0
                (v0 * tau - it.gravity * tau * tau / 2).coerceAtLeast(0.0)
            } ?: 0.0
            poseAt(t, rise)
        }
    }

    /** Real jump strategy, with the phases scripted: IDLE standing, TOP in the air, counted on landing. */
    private fun jumpStrategy(flights: List<Flight>): ExerciseStrategy {
        val real = JumpStrategy(ExerciseType.VERTICAL_JUMP, logger)
        return object : ExerciseStrategy by real {
            override fun detectPhase(
                poseResult: PoseResult,
                calibrationData: CalibrationData,
                currentState: ExercisePhaseState
            ): PhaseTransitionResult {
                val t = poseResult.timestamp
                val inAir = flights.any { t > it.takeoffMs && t < it.landingMs }
                val landed = flights.firstOrNull { t >= it.landingMs && t < it.landingMs + frameMs }
                return PhaseTransitionResult(
                    ExercisePhaseState(currentPhase = if (inAir) MovementPhase.TOP else MovementPhase.IDLE),
                    repCompleted = landed != null,
                    jump = landed?.let { JumpData.fromFlightTime(it.flightMs.toFloat()) }
                )
            }
        }
    }

    private fun analyze(flights: List<Flight>, type: ExerciseType = ExerciseType.VERTICAL_JUMP): SetSummary =
        runBlocking {
            val strategy = jumpStrategy(flights)
            val factory = mockk<ExerciseStrategyFactory> { every { getStrategy(any()) } returns strategy }
            val useCase = AnalyzeVideoUseCase(
                mockk(relaxed = true), factory, AnalyzeRepUseCase(factory, logger), mockk(relaxed = true), logger
            )
            val poses = video(flights)
            val source = object : VideoPoseSource {
                override val totalFrames = poses.size
                override fun poses(include: (Long) -> Boolean): Flow<VideoPoseSource.Frame> =
                    flow { poses.forEach { emit(VideoPoseSource.Frame(it.timestamp, it)) } }
                override fun close() = Unit
            }
            useCase.invoke(
                AnalyzeVideoUseCase.Params(
                    videoFile = File("jumps.mp4"),
                    exerciseType = type,
                    weight = Kilograms(0f),
                    targetReps = flights.size,
                    calibrationData = CalibrationData(
                        500f, userHeight, 0.45f, AnthropometricData.getDefaultRatios(userHeight, 75f)
                    )
                ),
                source,
                onProgress = {}
            ).getOrThrow()
        }

    @Test
    fun `free-fall jumps are OK and a mistracked one is SUSPECT, aligned with heights`() {
        val summary = analyze(flights(9.81, 5.0, 9.81))

        assertEquals(3, summary.repJumpHeights.size)
        assertEquals(listOf(JumpQuality.OK, JumpQuality.SUSPECT, JumpQuality.OK), summary.repJumpQualities)
        assertEquals(1, summary.excludedJumpCount)
        // The suspect jump flew longest, so it would have been the best.
        val longest = summary.repJumpHeights[1].value
        assertEquals(summary.repJumpHeights[0].value, summary.bestJumpHeight!!.value, 1e-4f)
        assertTrue(summary.bestJumpHeight!!.value < longest)
    }

    @Test
    fun `approach jumps can't be checked and keep counting`() {
        val summary = analyze(flights(9.81, 5.0), ExerciseType.APPROACH_JUMP)

        assertEquals(listOf(JumpQuality.UNCHECKED, JumpQuality.UNCHECKED), summary.repJumpQualities)
        assertEquals(0, summary.excludedJumpCount)
        assertEquals(summary.repJumpHeights[1].value, summary.bestJumpHeight!!.value, 1e-4f)
    }
}
