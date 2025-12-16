package com.lifthelper.domain.usecase

import com.lifthelper.domain.error.LiftHelperError
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.Set
import com.lifthelper.domain.model.Workout
import com.lifthelper.domain.repository.WorkoutRepository
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.value.RepCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

class SaveWorkoutUseCaseTest {

    private lateinit var workoutRepository: FakeWorkoutRepository
    private lateinit var logger: FakeLogger
    private lateinit var useCase: SaveWorkoutUseCase

    @Before
    fun setup() {
        workoutRepository = FakeWorkoutRepository()
        logger = FakeLogger()
        useCase = SaveWorkoutUseCase(workoutRepository, logger)
    }

    @Test
    fun `should save workout successfully when valid`() = runTest {
        // Given
        val workout = createValidWorkout()
        workoutRepository.shouldSucceed = true
        workoutRepository.returnId = 123L

        // When
        val result = useCase(SaveWorkoutUseCase.Params(workout))

        // Then
        assertTrue(result.isSuccess)
        assertEquals(123L, result.getOrNull())
        assertEquals(workout, workoutRepository.lastSavedWorkout)
    }

    @Test
    fun `should return validation error when workout has no sets`() = runTest {
        // Given
        val workout = Workout(
            date = Date(),
            duration = 1000L,
            sets = emptyList()  // Invalid - no sets
        )

        // When
        val result = useCase(SaveWorkoutUseCase.Params(workout))

        // Then
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is LiftHelperError.ValidationError)
        val validationError = exception as LiftHelperError.ValidationError
        assertEquals("sets", validationError.field)
        assertTrue(validationError.message.contains("at least one set"))
    }

    @Test
    fun `should log warning when validation fails`() = runTest {
        // Given
        val workout = Workout(
            date = Date(),
            duration = 1000L,
            sets = emptyList()
        )

        // When
        useCase(SaveWorkoutUseCase.Params(workout))

        // Then
        assertTrue(logger.warnings.isNotEmpty())
        assertTrue(logger.warnings[0].contains("Validation failed"))
    }

    @Test
    fun `should propagate repository error when save fails`() = runTest {
        // Given
        val workout = createValidWorkout()
        val databaseError = LiftHelperError.DatabaseError("Save failed", null)
        workoutRepository.shouldSucceed = false
        workoutRepository.errorToReturn = databaseError

        // When
        val result = useCase(SaveWorkoutUseCase.Params(workout))

        // Then
        assertTrue(result.isFailure)
        assertEquals(databaseError, result.exceptionOrNull())
    }

    @Test
    fun `should log error when repository save fails`() = runTest {
        // Given
        val workout = createValidWorkout()
        val databaseError = LiftHelperError.DatabaseError("Save failed", null)
        workoutRepository.shouldSucceed = false
        workoutRepository.errorToReturn = databaseError

        // When
        useCase(SaveWorkoutUseCase.Params(workout))

        // Then
        assertTrue(logger.errors.isNotEmpty())
        assertTrue(logger.errors[0].contains("Failed to save workout"))
    }

    @Test
    fun `should save workout with single set`() = runTest {
        // Given
        val set = createTestSet()
        val workout = Workout(
            date = Date(),
            duration = 1000L,
            sets = listOf(set)
        )
        workoutRepository.shouldSucceed = true

        // When
        val result = useCase(SaveWorkoutUseCase.Params(workout))

        // Then
        assertTrue(result.isSuccess)
    }

    @Test
    fun `should save workout with multiple sets`() = runTest {
        // Given
        val sets = listOf(createTestSet(), createTestSet(), createTestSet())
        val workout = Workout(
            date = Date(),
            duration = 3000L,
            sets = sets
        )
        workoutRepository.shouldSucceed = true

        // When
        val result = useCase(SaveWorkoutUseCase.Params(workout))

        // Then
        assertTrue(result.isSuccess)
        assertEquals(workout, workoutRepository.lastSavedWorkout)
    }

    // Helper functions
    private fun createValidWorkout(): Workout {
        return Workout(
            date = Date(),
            duration = 1000L,
            notes = "Test workout",
            sets = listOf(createTestSet())
        )
    }

    private fun createTestSet(): Set {
        return Set(
            workoutId = 0,
            exerciseType = ExerciseType.BACK_SQUAT,
            weight = Kilograms(100f),
            targetReps = RepCount(5),
            completedReps = RepCount(5),
            avgVelocity = MetersPerSecond(0.6f),
            peakVelocity = MetersPerSecond(1.0f),
            timestamp = System.currentTimeMillis()
        )
    }

    // Fake implementations for testing
    private class FakeWorkoutRepository : WorkoutRepository {
        var shouldSucceed = true
        var returnId = 1L
        var errorToReturn: Throwable? = null
        var lastSavedWorkout: Workout? = null

        override suspend fun saveWorkout(workout: Workout): Result<Long> {
            lastSavedWorkout = workout
            return if (shouldSucceed) {
                Result.success(returnId)
            } else {
                Result.failure(errorToReturn ?: Exception("Unknown error"))
            }
        }

        override fun getWorkouts(): Flow<List<Workout>> {
            throw NotImplementedError()
        }

        override suspend fun getWorkoutById(id: Long): Result<Workout> {
            throw NotImplementedError()
        }

        override suspend fun deleteWorkout(id: Long): Result<Unit> {
            throw NotImplementedError()
        }
    }

    private class FakeLogger : Logger {
        val debugMessages = mutableListOf<String>()
        val infoMessages = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun d(tag: String, message: String) {
            debugMessages.add(message)
        }

        override fun i(tag: String, message: String) {
            infoMessages.add(message)
        }

        override fun w(tag: String, message: String) {
            warnings.add(message)
        }

        override fun e(tag: String, message: String, throwable: Throwable?) {
            errors.add(message)
        }
    }
}
