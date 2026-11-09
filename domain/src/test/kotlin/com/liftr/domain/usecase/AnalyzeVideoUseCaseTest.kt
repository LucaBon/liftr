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
import com.liftr.domain.model.BarTrackSource
import com.liftr.domain.model.SetSummary
import com.liftr.domain.repository.SettingsRepository
import com.liftr.domain.vision.IPositionCalculator
import com.liftr.domain.vision.PlateObservation
import com.liftr.domain.vision.VideoPoseSource
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnalyzeVideoUseCaseTest {

    private val imageHeight = 1000
    private val pixelsPerMeter = 1000f

    /**
     * Shoulders (the tracked point) at normalized [y]; the head above and the
     * hips and legs below, so the squat's plausibility gate keeps the frame.
     */
    private fun poseAt(y: Float, timestampMs: Long): PoseResult {
        val landmarks = List(33) { i ->
            val dy = when {
                i <= PoseResult.RIGHT_EAR -> -0.05f
                i < PoseResult.LEFT_HIP -> 0f
                else -> 0.15f
            }
            PoseKeypoint(0.5f, y + dy, 0f, 1f)
        }
        return PoseResult(landmarks, timestampMs, imageWidth = 500, imageHeight = imageHeight)
    }

    /** Same contract as the real calculator: normalized y in, meters out. */
    private fun calculator(): IPositionCalculator {
        val calculator = mockk<IPositionCalculator> {
            justRun { resetScale() }
            every { pixelsToMeters(any(), any(), any()) } answers {
                firstArg<Float>() * secondArg<PoseResult>().imageHeight / pixelsPerMeter
            }
        }
        every { calculator.newSession() } returns calculator
        return calculator
    }

    @Test
    fun `a tracked point moving 0_5 m in one second reads about 0_5 m per second`() = runBlocking {
        val calculator = calculator()
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

    // A lift whose phases come from the strategy; squats take theirs from the bar track.
    @Test
    fun `rep mean velocity covers only the concentric part`() = runBlocking {
        val calculator = calculator()
        // Frame 0 idle, 1..40 slow drift (not counted), 41..60 fast ascent (a clean counts ECCENTRIC), 61 lockout.
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
                    i <= 40 -> MovementPhase.TOP
                    i <= 60 -> MovementPhase.ECCENTRIC
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
                exerciseType = ExerciseType.CLEAN,
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

    /**
     * Two squats at 30 fps, as the drop below standing (m) per frame: 1 s
     * standing, 0.6 m down at 0.5 m/s, 0.3 s paused, up at 0.6 m/s, 1.5 s
     * standing; then the same again.
     */
    private fun twoSquats(): List<Float> {
        val drops = mutableListOf<Float>()
        repeat(2) {
            repeat(30) { drops += 0f }
            repeat(36) { drops += 0.6f * (it + 1) / 36 }
            repeat(9) { drops += 0.6f }
            repeat(30) { drops += 0.6f - 0.6f * (it + 1) / 30 }
            repeat(15) { drops += 0f }
        }
        repeat(15) { drops += 0f }
        return drops
    }

    private suspend fun analyzeSquat(
        poses: List<PoseResult>,
        plates: List<PlateObservation?>?,
        exerciseType: ExerciseType = ExerciseType.FRONT_SQUAT
    ): SetSummary {
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { getFloatSetting(any(), any()) } returns flowOf(0.45f)
        }
        val strategy = mockk<ExerciseStrategy>(relaxed = true)
        val factory = mockk<ExerciseStrategyFactory> { every { getStrategy(any()) } returns strategy }
        val useCase = AnalyzeVideoUseCase(calculator(), factory, mockk(relaxed = true), settings, mockk(relaxed = true))
        val source = object : VideoPoseSource {
            override val totalFrames = poses.size
            override fun poses(include: (Long) -> Boolean): Flow<VideoPoseSource.Frame> = flow {
                poses.forEachIndexed { i, pose -> emit(VideoPoseSource.Frame(pose.timestamp, pose, plates?.get(i))) }
            }
            override fun close() = Unit
        }
        return useCase.invoke(
            AnalyzeVideoUseCase.Params(
                videoFile = File("set.mp4"),
                exerciseType = exerciseType,
                weight = Kilograms(100f),
                targetReps = 2,
                calibrationData = CalibrationData(pixelsPerMeter, 1.75f, 0.45f)
            ),
            source,
            onProgress = {}
        ).getOrThrow()
    }

    private fun assertTwoSquats(summary: SetSummary) {
        assertEquals(2, summary.completedReps.value)
        for (n in 0..1) {
            assertEquals(0.6f, summary.repRangeOfMotion[n].value, 0.02f)
            assertEquals(0.5f, summary.repEccentricVelocities[n].value, 0.06f)
            assertEquals(1.2f, summary.repEccentricDurations[n], 0.1f)
            assertEquals(300f, summary.repPauseMs[n].toFloat(), 70f)
            // Mean concentric velocity: the ascent's travel over its time.
            assertEquals(0.6f, summary.repVelocities[n].value, 0.08f)
            assertEquals(1.0f, summary.repConcentricDurations[n], 0.15f)
            assertTrue(summary.repPeakVelocities[n].value in 0.55f..0.9f)
        }
    }

    @Test
    fun `a squat's reps and phases come from the plate when it is tracked`() = runBlocking {
        // The landmarks stand still; only the plate moves. Plate radius 0.15 of
        // the 500 px width = 75 px for 0.225 m: 333 px/m, and 1000 px of height.
        val drops = twoSquats()
        val poses = drops.indices.map { poseAt(0.3f, it * 33L) }
        val plates = drops.map { PlateObservation(0.5f, 0.3f + it * 333.33f / imageHeight, 0.15f, 1f) }

        val summary = analyzeSquat(poses, plates)

        assertEquals(BarTrackSource.PLATE, summary.barTrackSource)
        assertTwoSquats(summary)
    }

    @Test
    fun `without a plate a squat is measured on the landmarks`() = runBlocking {
        val drops = twoSquats()
        // Shoulders drop with the bar, at 1000 px/m.
        val poses = drops.mapIndexed { i, drop -> poseAt(0.2f + drop, i * 33L) }

        val summary = analyzeSquat(poses, plates = null)

        assertEquals(BarTrackSource.LANDMARKS, summary.barTrackSource)
        assertTwoSquats(summary)
    }

    /**
     * Three bench presses at 30 fps, as the drop below the top (m): 1 s at the
     * top, 0.3 m down in 1 s, [pauses] s on the chest, up in 0.8 s, 1 s at the top.
     */
    private fun threeBenches(): List<Float> {
        val drops = mutableListOf<Float>()
        repeat(30) { drops += 0f }
        for (pause in listOf(0f, 0.5f, 0f)) {
            repeat(30) { drops += 0.3f * (it + 1) / 30 }
            repeat((pause * 30).toInt()) { drops += 0.3f }
            repeat(24) { drops += 0.3f - 0.3f * (it + 1) / 24 }
            repeat(30) { drops += 0f }
        }
        return drops
    }

    private fun assertThreeBenches(summary: SetSummary) {
        assertEquals(3, summary.completedReps.value)
        for (n in 0..2) {
            assertEquals(0.3f, summary.repRangeOfMotion[n].value, 0.02f)
            assertEquals(0.3f, summary.repEccentricVelocities[n].value, 0.05f)
            assertEquals(0.375f, summary.repVelocities[n].value, 0.07f)
        }
        assertEquals(0f, summary.repPauseMs[0].toFloat(), 100f)
        assertEquals(500f, summary.repPauseMs[1].toFloat(), 80f)
        assertEquals(0f, summary.repPauseMs[2].toFloat(), 100f)
    }

    @Test
    fun `a bench press's reps and chest pause come from the plate`() = runBlocking {
        val drops = threeBenches()
        val poses = drops.indices.map { poseAt(0.3f, it * 33L) }
        val plates = drops.map { PlateObservation(0.5f, 0.3f + it * 333.33f / imageHeight, 0.15f, 1f) }

        val summary = analyzeSquat(poses, plates, ExerciseType.BENCH_PRESS)

        assertEquals(BarTrackSource.PLATE, summary.barTrackSource)
        assertThreeBenches(summary)
    }

    @Test
    fun `without a plate a bench press is measured on the wrists`() = runBlocking {
        val drops = threeBenches()
        val poses = drops.mapIndexed { i, drop -> poseAt(0.2f + drop, i * 33L) }

        val summary = analyzeSquat(poses, plates = null, exerciseType = ExerciseType.BENCH_PRESS)

        assertEquals(BarTrackSource.LANDMARKS, summary.barTrackSource)
        assertThreeBenches(summary)
    }

    /** Three deadlifts at 30 fps as the bar's height above the floor (m): 1 s on it, pulls of 0.5 m, rep 2 a dead stop. */
    private fun threeDeadlifts(): List<Float> {
        val heights = mutableListOf<Float>()
        repeat(30) { heights += 0f }
        for (rest in listOf(0, 30, 0)) {
            repeat(rest) { heights += 0f }
            repeat(30) { heights += 0.5f * (it + 1) / 30 }
            repeat(30) { heights += 0.5f }
            repeat(30) { heights += 0.5f - 0.5f * (it + 1) / 30 }
        }
        repeat(30) { heights += 0f }
        return heights
    }

    @Test
    fun `a deadlift's pulls and floor rests come from the plate`() = runBlocking {
        val heights = threeDeadlifts()
        val poses = heights.indices.map { poseAt(0.5f, it * 33L) }
        // The plate rises 0.5 m = 167 px of 1000 from cy 0.5.
        val plates = heights.map { PlateObservation(0.5f, 0.5f - it * 333.33f / imageHeight, 0.15f, 1f) }

        val summary = analyzeSquat(poses, plates, ExerciseType.DEADLIFT)

        assertEquals(BarTrackSource.PLATE, summary.barTrackSource)
        assertEquals(3, summary.completedReps.value)
        for (n in 0..2) {
            assertEquals(0.5f, summary.repRangeOfMotion[n].value, 0.03f)
            // The pull is the concentric, the lowering the eccentric.
            assertEquals(0.5f, summary.repVelocities[n].value, 0.08f)
            assertEquals(0.5f, summary.repEccentricVelocities[n].value, 0.08f)
            assertEquals(1.0f, summary.repConcentricDurations[n], 0.15f)
        }
        assertEquals(0f, summary.repPauseMs[0].toFloat(), 1f)
        assertEquals(1000f, summary.repPauseMs[1].toFloat(), 150f)
        assertEquals(0f, summary.repPauseMs[2].toFloat(), 150f)
    }

    @Test
    fun `a plate seen on too few of the rep frames falls back to the landmarks`() = runBlocking {
        val drops = twoSquats()
        val poses = drops.mapIndexed { i, drop -> poseAt(0.2f + drop, i * 33L) }
        // Seen on every third frame only: its gaps are short and get bridged, but it is mostly guessed.
        val plates = drops.mapIndexed { i, drop ->
            if (i % 3 == 0) PlateObservation(0.5f, 0.3f + drop * 333.33f / imageHeight, 0.15f, 1f) else null
        }

        val summary = analyzeSquat(poses, plates)

        assertEquals(BarTrackSource.LANDMARKS, summary.barTrackSource)
        assertEquals(2, summary.completedReps.value)
    }

    @Test
    fun `an air squat is counted from the landmarks without any plate`() = runBlocking {
        val drops = twoSquats()
        val poses = drops.mapIndexed { i, drop -> poseAt(0.2f + drop, i * 33L) }

        val summary = analyzeSquat(poses, plates = null, exerciseType = ExerciseType.AIR_SQUAT)

        assertEquals(BarTrackSource.LANDMARKS, summary.barTrackSource)
        assertTwoSquats(summary)
    }
}
