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
import com.liftr.domain.model.SetSummary
import com.liftr.domain.value.Kilograms
import com.liftr.domain.vision.EndcapDetection
import com.liftr.domain.vision.IPositionCalculator
import com.liftr.domain.vision.VideoPoseSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** Body (all landmarks) height per frame: idle, slow descent 1..40, fast ascent 41..60, lockout 61. */
    private val squatYs = (0..61).map {
        when {
            it <= 40 -> 0.6f + 0.1f * it / 40f
            else -> 0.7f - 0.5f * (it - 40) / 20f
        }
    }

    /** The endcap moves half as far as the body, so the two series are told apart. */
    private fun endcapY(frame: Int) = 0.62f + (squatYs[frame] - 0.6f) * 0.5f

    private class SquatRun(val summary: SetSummary, val annotations: List<FrameAnnotation>)

    /** One back squat rep, with [endcapsFor] giving each frame's detections. */
    private fun runSquat(endcapsFor: (Int) -> List<EndcapDetection>): SquatRun = runBlocking {
        val calculator = mockk<IPositionCalculator> {
            every { pixelsToMeters(any(), any(), any()) } answers {
                firstArg<Float>() * secondArg<PoseResult>().imageHeight / pixelsPerMeter
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
                PhaseTransitionResult(ExercisePhaseState(currentPhase = phase), repCompleted = i == 61)
            }
        }
        val factory = mockk<ExerciseStrategyFactory> { every { getStrategy(any()) } returns strategy }
        val useCase = AnalyzeVideoUseCase(
            calculator, factory, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
        )
        val poses = squatYs.mapIndexed { i, y -> poseAt(y, i * 50L) }
        val source = object : VideoPoseSource {
            override val totalFrames = poses.size
            override fun poses(include: (Long) -> Boolean): Flow<VideoPoseSource.Frame> = flow {
                poses.forEachIndexed { i, pose -> emit(VideoPoseSource.Frame(pose.timestamp, pose, endcapsFor(i))) }
            }
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
        SquatRun(result.getOrThrow(), annotations)
    }

    private fun endcapAt(frame: Int) = listOf(EndcapDetection(0.5f, endcapY(frame), 0.02f, 0.02f, 0.6f))

    @Test
    fun `the pose series is the same with or without endcaps`() {
        val withoutEndcaps = runSquat { emptyList() }
        val withEndcaps = runSquat(::endcapAt)

        assertEquals(
            withoutEndcaps.annotations.map { it.velocity.value },
            withEndcaps.annotations.map { it.velocity.value }
        )
        val a = withoutEndcaps.summary
        val b = withEndcaps.summary
        assertEquals(a.repVelocities, b.repVelocities)
        assertEquals(a.repPeakVelocities, b.repPeakVelocities)
        assertEquals(a.repBarPaths, b.repBarPaths)
        assertEquals(a.repScores, b.repScores)
        assertEquals(a.copy(barSeries = emptyList(), repBars = emptyList()), b.copy(barSeries = emptyList(), repBars = emptyList()))
        assertTrue(a.barSeries.isEmpty() && a.repBars.isEmpty())
    }

    @Test
    fun `rep bar velocities come from the endcap over the concentric frames`() {
        val run = runSquat(::endcapAt)
        val bar = run.summary.repBars.single()

        assertEquals(1f, bar.concentricCoverage, 1e-6f)
        val ascent = run.summary.barSeries.filter { it.t in 41 * 50L..60 * 50L }.map { it.velocity!! }
        assertEquals(ascent.average().toFloat(), bar.meanConcentricVelocity!!, 1e-4f)
        assertEquals(ascent.max(), bar.peakConcentricVelocity!!, 1e-6f)
        // Half the body's movement: clearly slower than the pose-based rep velocity.
        assertTrue(bar.meanConcentricVelocity!! < run.summary.repVelocities.single().value * 0.75f)
    }

    @Test
    fun `rep bar velocities are null when the endcap covers too little of the concentric phase`() {
        // The endcap disappears halfway up the ascent.
        val run = runSquat { i -> if (i <= 50) endcapAt(i) else emptyList() }
        val bar = run.summary.repBars.single()

        assertTrue(bar.concentricCoverage < 0.8f)
        assertNull(bar.meanConcentricVelocity)
        assertNull(bar.peakConcentricVelocity)
        // The pose-based rep velocity is still there.
        assertTrue(run.summary.repVelocities.single().value > 0f)
    }

    @Test
    fun `endcap gaps stay gaps in the bar series`() {
        // No endcap for frames 20..29: longer than the tracker's hold.
        val run = runSquat { i -> if (i in 20..29) emptyList() else endcapAt(i) }
        val times = run.summary.barSeries.map { it.t }.toSet()

        val missing = (0..61).filter { it * 50L !in times }
        // The first three frames of the gap are held, the rest is missing; nothing filled from the pose.
        assertEquals((23..29).toList(), missing)
        run.summary.barSeries.forEach { sample ->
            val frame = (sample.t / 50L).toInt()
            assertEquals(if (sample.held) endcapY(19) else endcapY(frame), sample.y, 0.02f)
        }
    }
}
