package com.liftr.domain.usecase

import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseStrategy
import com.liftr.domain.exercise.ExerciseStrategyFactory
import com.liftr.domain.exercise.PhaseTransitionResult
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.FrameAnnotation
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.value.Kilograms
import com.liftr.domain.vision.IPositionCalculator
import com.liftr.domain.vision.VideoPoseSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AnalyzeVideoUseCaseTest {

    private val imageHeight = 1000
    private val pixelsPerMeter = 1000f

    /** Shoulders at normalized [y]; the rest of the body is irrelevant to the tracked point. */
    private fun poseAt(y: Float, timestampMs: Long): PoseResult {
        val landmarks = List(33) { PoseKeypoint(0.5f, y, 0f, 1f) }
        return PoseResult(landmarks, timestampMs, imageWidth = 500, imageHeight = imageHeight)
    }

    @Test
    fun `a tracked point moving 0_5 m in one second reads about 0_5 m per second`() = runBlocking {
        // Same contract as the real calculator: normalized y in, meters out.
        val calculator = mockk<IPositionCalculator> {
            every { pixelsToMeters(any(), any(), any()) } answers {
                firstArg<Float>() * secondArg<PoseResult>().imageHeight / pixelsPerMeter
            }
        }
        val strategy = mockk<ExerciseStrategy> {
            every { detectPhase(any(), any(), any()) } returns
                PhaseTransitionResult(ExercisePhaseState.initial(), repCompleted = false)
        }
        val factory = mockk<ExerciseStrategyFactory> { every { getStrategy(any()) } returns strategy }
        val useCase = AnalyzeVideoUseCase(
            calculator, factory, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
        )

        // 21 frames over 1 s; the shoulders drop from y=0.2 to y=0.7 (0.5 m at 1000 px/m).
        val poses = (0..20).map { poseAt(0.2f + 0.5f * it / 20f, it * 50L) }
        val source = object : VideoPoseSource {
            override val totalFrames = poses.size
            override fun poses(include: (Long) -> Boolean): Flow<VideoPoseSource.Frame> =
                flow { poses.forEach { emit(VideoPoseSource.Frame(it.timestamp, it)) } }
            override fun close() = Unit
        }

        val annotations = mutableListOf<FrameAnnotation>()
        val result = useCase.invoke(
            AnalyzeVideoUseCase.Params(
                videoFile = File("set.mp4"),
                exerciseType = ExerciseType.BACK_SQUAT,
                weight = Kilograms(100f),
                targetReps = 1,
                calibrationData = CalibrationData(pixelsPerMeter, 1.75f, 0.45f)
            ),
            source,
            onProgress = {},
            onFrameAnnotation = { annotations += it }
        )

        assertEquals(true, result.isSuccess)
        // Skip the filter's settling at both ends.
        val middle = annotations.subList(5, 16).map { it.velocity.value }
        assertEquals(0.5f, middle.average().toFloat(), 0.1f)
    }

    @Test
    fun `rep mean velocity covers only the concentric part`() = runBlocking {
        val calculator = mockk<IPositionCalculator> {
            every { pixelsToMeters(any(), any(), any()) } answers {
                firstArg<Float>() * secondArg<PoseResult>().imageHeight / pixelsPerMeter
            }
        }
        // Frame 0 idle, 1..40 slow descent, 41..60 fast ascent, 61 lockout.
        val ys = (0..61).map {
            when {
                it <= 40 -> 0.6f + 0.1f * it / 40f
                else -> 0.7f - 0.5f * (it - 40) / 20f
            }
        }
        var frame = 0
        val strategy = mockk<ExerciseStrategy> {
            every { detectPhase(any(), any(), any()) } answers {
                val i = frame++
                val phase = when {
                    i == 0 -> MovementPhase.IDLE
                    i <= 40 -> MovementPhase.ECCENTRIC
                    i <= 60 -> MovementPhase.CONCENTRIC
                    else -> MovementPhase.TOP
                }
                PhaseTransitionResult(
                    ExercisePhaseState(currentPhase = phase), repCompleted = i == 61
                )
            }
        }
        val factory = mockk<ExerciseStrategyFactory> { every { getStrategy(any()) } returns strategy }
        val useCase = AnalyzeVideoUseCase(
            calculator, factory, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
        )
        val poses = ys.mapIndexed { i, y -> poseAt(y, i * 50L) }
        val source = object : VideoPoseSource {
            override val totalFrames = poses.size
            override fun poses(include: (Long) -> Boolean): Flow<VideoPoseSource.Frame> =
                flow { poses.forEach { emit(VideoPoseSource.Frame(it.timestamp, it)) } }
            override fun close() = Unit
        }

        val annotations = mutableListOf<FrameAnnotation>()
        val result = useCase.invoke(
            AnalyzeVideoUseCase.Params(
                videoFile = File("set.mp4"),
                exerciseType = ExerciseType.BACK_SQUAT,
                weight = Kilograms(100f),
                targetReps = 1,
                calibrationData = CalibrationData(pixelsPerMeter, 1.75f, 0.45f)
            ),
            source,
            onProgress = {},
            onFrameAnnotation = { annotations += it }
        )

        assertEquals(true, result.isSuccess)
        val repVelocities = result.getOrThrow().repVelocities
        assertEquals(1, repVelocities.size)
        // The mean of the per-frame velocities over the ascent frames only (41..60).
        val ascent = annotations.filter { it.timestampMs in 41 * 50L..60 * 50L }.map { it.velocity.value }
        val all = annotations.map { it.velocity.value }
        assertEquals(20, ascent.size)
        assertEquals(ascent.average().toFloat(), repVelocities[0].value, 0.01f)
        // Averaging the slow descent in as well would read clearly lower.
        assertEquals(true, repVelocities[0].value > all.average().toFloat() + 0.05f)
        // The rep peak is the fastest ascent frame, not the fastest of the whole rep.
        val summary = result.getOrThrow()
        assertEquals(1, summary.repPeakVelocities.size)
        assertEquals(ascent.max(), summary.repPeakVelocities[0].value, 0.01f)
        assertEquals(summary.repPeakVelocities[0].value, summary.peakVelocity.value, 0.0001f)
    }

    @Test
    fun `concentric phases per exercise`() {
        val c = MovementPhase.CONCENTRIC
        assertEquals(true, ExerciseType.BACK_SQUAT.isConcentricPhase(c))
        assertEquals(false, ExerciseType.BACK_SQUAT.isConcentricPhase(MovementPhase.ECCENTRIC))
        assertEquals(true, ExerciseType.DEADLIFT.isConcentricPhase(c))
        assertEquals(false, ExerciseType.DEADLIFT.isConcentricPhase(MovementPhase.ECCENTRIC))
        for (t in listOf(ExerciseType.CLEAN, ExerciseType.SNATCH)) {
            assertEquals(true, t.isConcentricPhase(MovementPhase.ECCENTRIC))
            assertEquals(true, t.isConcentricPhase(MovementPhase.BOTTOM))
            assertEquals(false, t.isConcentricPhase(c))
        }
    }
}
