package com.lifthelper.domain.usecase

import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.ExportConfig
import com.lifthelper.domain.model.ExportFormat
import com.lifthelper.domain.model.Rep
import com.lifthelper.domain.model.Set
import com.lifthelper.domain.model.Workout
import com.lifthelper.domain.repository.WorkoutRepository
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.value.RepCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

class GenerateExportUseCaseTest {

    private lateinit var repository: FakeWorkoutRepository
    private lateinit var useCase: GenerateExportUseCase

    @Before
    fun setUp() {
        repository = FakeWorkoutRepository()
        useCase = GenerateExportUseCase(repository)
    }

    @Test
    fun `csv sets export includes header and one row per set`() = runTest {
        repository.workouts = listOf(
            workout(
                date = Date(1_700_000_000_000L),
                sets = listOf(
                    squatSet(weight = 100f, reps = 5),
                    squatSet(weight = 105f, reps = 3)
                )
            )
        )

        val result = useCase(ExportConfig(format = ExportFormat.CSV_SETS))

        val lines = result.content.trim().lines()
        assertEquals(3, lines.size) // header + 2 sets
        assertTrue(lines[0].startsWith("date,exercise,weight_kg"))
        assertTrue(lines[1].contains("BACK_SQUAT") || lines[1].contains("Back Squat"))
        assertEquals(1, result.workoutCount)
        assertEquals(2, result.setCount)
        assertTrue(result.fileName.endsWith(".csv"))
        assertEquals("text/csv", result.mimeType)
    }

    @Test
    fun `csv reps export emits one row per rep with running set number`() = runTest {
        repository.workouts = listOf(
            workout(
                date = Date(1_700_000_000_000L),
                sets = listOf(
                    squatSet(weight = 100f, reps = 3),
                    squatSet(weight = 100f, reps = 2)
                )
            )
        )

        val result = useCase(ExportConfig(format = ExportFormat.CSV_REPS))

        val lines = result.content.trim().lines()
        assertEquals(6, lines.size) // header + 5 reps total
        assertTrue(lines[0].startsWith("date,exercise,weight_kg,set_number,rep_number"))
    }

    @Test
    fun `json export produces valid parseable structure`() = runTest {
        repository.workouts = listOf(
            workout(
                date = Date(1_700_000_000_000L),
                sets = listOf(squatSet(weight = 100f, reps = 5))
            )
        )

        val result = useCase(ExportConfig(format = ExportFormat.JSON))

        val root: JsonObject = Json.parseToJsonElement(result.content).jsonObject
        val workouts: JsonArray = root["workouts"]!!.jsonArray
        assertEquals(1, workouts.size)
        val sets = workouts[0].jsonObject["sets"]!!.jsonArray
        assertEquals(1, sets.size)
        assertEquals("BACK_SQUAT", sets[0].jsonObject["exercise"]!!.jsonPrimitive.content)
        assertEquals("application/json", result.mimeType)
    }

    @Test
    fun `date range filter excludes out-of-range workouts`() = runTest {
        val now = System.currentTimeMillis()
        val oldDate = Date(now - 200L * 24 * 3600 * 1000)
        val recent = Date(now - 5L * 24 * 3600 * 1000)
        repository.workouts = listOf(
            workout(date = oldDate, sets = listOf(squatSet(100f, 5))),
            workout(date = recent, sets = listOf(squatSet(100f, 5)))
        )

        val result = useCase(
            ExportConfig(
                format = ExportFormat.CSV_SETS,
                startDate = now - 30L * 24 * 3600 * 1000
            )
        )

        assertEquals(1, result.workoutCount)
        assertEquals(1, result.setCount)
    }

    @Test
    fun `exercise filter excludes non-matching sets and empty workouts`() = runTest {
        repository.workouts = listOf(
            workout(
                date = Date(),
                sets = listOf(
                    squatSet(100f, 5),
                    benchSet(80f, 5)
                )
            ),
            workout(
                date = Date(),
                sets = listOf(benchSet(85f, 5))
            )
        )

        val result = useCase(
            ExportConfig(
                format = ExportFormat.CSV_SETS,
                exerciseType = ExerciseType.BACK_SQUAT
            )
        )

        assertEquals(1, result.workoutCount)
        assertEquals(1, result.setCount)
        assertFalse(result.content.contains("BENCH"))
    }

    @Test
    fun `empty input yields header-only csv and zero counts`() = runTest {
        repository.workouts = emptyList()

        val result = useCase(ExportConfig(format = ExportFormat.CSV_SETS))

        assertEquals(0, result.workoutCount)
        assertEquals(0, result.setCount)
        val lines = result.content.trim().lines()
        assertEquals(1, lines.size) // header only
    }

    @Test
    fun `csv notes with commas and quotes are escaped`() = runTest {
        repository.workouts = listOf(
            workout(
                date = Date(1_700_000_000_000L),
                sets = listOf(
                    squatSet(100f, 5, notes = "hard, grinding \"rep 5\"")
                )
            )
        )

        val result = useCase(ExportConfig(format = ExportFormat.CSV_SETS))

        assertTrue(result.content.contains("\"hard, grinding \"\"rep 5\"\"\""))
    }

    private fun workout(date: Date, sets: List<Set>): Workout =
        Workout(id = 0, date = date, duration = 60_000L, sets = sets)

    private fun squatSet(weight: Float, reps: Int, notes: String = ""): Set =
        Set(
            workoutId = 0,
            exerciseType = ExerciseType.BACK_SQUAT,
            weight = Kilograms(weight),
            targetReps = RepCount(reps),
            completedReps = RepCount(reps),
            avgVelocity = MetersPerSecond(0.7f),
            peakVelocity = MetersPerSecond(1.0f),
            timestamp = System.currentTimeMillis(),
            notes = notes,
            reps = (1..reps).map { repOf(it) }
        )

    private fun benchSet(weight: Float, reps: Int): Set =
        Set(
            workoutId = 0,
            exerciseType = ExerciseType.BENCH_PRESS,
            weight = Kilograms(weight),
            targetReps = RepCount(reps),
            completedReps = RepCount(reps),
            avgVelocity = MetersPerSecond(0.5f),
            peakVelocity = MetersPerSecond(0.8f),
            timestamp = System.currentTimeMillis(),
            reps = (1..reps).map { repOf(it) }
        )

    private fun repOf(n: Int): Rep = Rep(
        setId = 0,
        repNumber = n,
        peakVelocity = MetersPerSecond(1.0f),
        avgVelocity = MetersPerSecond(0.7f),
        timeUnderTension = 2f,
        rangeOfMotion = Meters(0.5f),
        startPosition = Meters(0f),
        endPosition = Meters(0.5f),
        eccentricDuration = 1.2f,
        concentricDuration = 0.8f,
        velocityProfile = emptyList(),
        positionProfile = emptyList(),
        timestamp = System.currentTimeMillis(),
        qualityScore = 80
    )

    private class FakeWorkoutRepository : WorkoutRepository {
        var workouts: List<Workout> = emptyList()

        override suspend fun saveWorkout(workout: Workout): Result<Long> =
            Result.success(workout.id)

        override fun getWorkouts(): Flow<List<Workout>> = flowOf(workouts)

        override suspend fun getWorkoutById(id: Long): Result<Workout> =
            Result.failure(NotImplementedError())

        override suspend fun deleteWorkout(id: Long): Result<Unit> =
            Result.success(Unit)
    }
}
