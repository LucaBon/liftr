package com.lifthelper.data.repository

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lifthelper.data.local.database.dao.WorkoutDao
import com.lifthelper.data.local.database.entities.WorkoutEntity
import com.lifthelper.data.mapper.WorkoutMapper
import com.lifthelper.domain.error.LiftHelperError
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.Set
import com.lifthelper.domain.model.Workout
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.value.RepCount
import com.lifthelper.domain.value.Watts
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Date

/**
 * Integration tests for WorkoutRepositoryImpl.
 *
 * Tests the repository layer's integration with DAOs and mappers,
 * including error handling and data transformation.
 */
class WorkoutRepositoryImplTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var workoutDao: WorkoutDao
    private lateinit var mapper: WorkoutMapper
    private lateinit var logger: Logger
    private lateinit var repository: WorkoutRepositoryImpl

    private val testWorkoutEntity = WorkoutEntity(
        id = 1,
        date = System.currentTimeMillis(),
        duration = 3600000,
        notes = "Test workout",
        createdAt = System.currentTimeMillis()
    )

    private val testWorkout = Workout(
        id = 1,
        date = Date(System.currentTimeMillis()),
        duration = 3600000,
        notes = "Test workout",
        sets = emptyList(),
        createdAt = System.currentTimeMillis()
    )

    @Before
    fun setup() {
        workoutDao = mockk()
        mapper = mockk()
        logger = mockk(relaxed = true) // Relaxed to avoid verifying every log call
        repository = WorkoutRepositoryImpl(workoutDao, mapper, logger)
    }

    @Test
    fun `saveWorkout should return success with workout ID when save succeeds`() = runTest {
        // Given
        val expectedId = 1L
        every { mapper.toEntity(testWorkout) } returns testWorkoutEntity
        coEvery { workoutDao.insertWorkout(testWorkoutEntity) } returns expectedId

        // When
        val result = repository.saveWorkout(testWorkout)

        // Then
        assertTrue(result.isSuccess)
        assertEquals(expectedId, result.getOrNull())
        coVerify { workoutDao.insertWorkout(testWorkoutEntity) }
        verify { mapper.toEntity(testWorkout) }
    }

    @Test
    fun `saveWorkout should return database error when DAO throws exception`() = runTest {
        // Given
        val exception = RuntimeException("Database error")
        every { mapper.toEntity(testWorkout) } returns testWorkoutEntity
        coEvery { workoutDao.insertWorkout(testWorkoutEntity) } throws exception

        // When
        val result = repository.saveWorkout(testWorkout)

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftHelperError.DatabaseError)
        assertEquals("Unexpected error saving workout", error?.message)
    }

    @Test
    fun `getWorkouts should return flow of workouts`() = runTest {
        // Given
        val entities = listOf(testWorkoutEntity)
        val workouts = listOf(testWorkout)
        every { workoutDao.getAllWorkouts() } returns flowOf(entities)
        every { mapper.toDomainList(entities) } returns workouts

        // When
        repository.getWorkouts().test {
            // Then
            val emittedWorkouts = awaitItem()
            assertEquals(workouts, emittedWorkouts)
            assertEquals(1, emittedWorkouts.size)
            assertEquals(testWorkout.id, emittedWorkouts[0].id)
            awaitComplete()
        }

        verify { workoutDao.getAllWorkouts() }
        verify { mapper.toDomainList(entities) }
    }

    @Test
    fun `getWorkouts should return empty list when no workouts exist`() = runTest {
        // Given
        every { workoutDao.getAllWorkouts() } returns flowOf(emptyList())
        every { mapper.toDomainList(emptyList()) } returns emptyList()

        // When
        repository.getWorkouts().test {
            // Then
            val emittedWorkouts = awaitItem()
            assertTrue(emittedWorkouts.isEmpty())
            awaitComplete()
        }
    }

    @Test
    fun `getWorkoutById should return workout when found`() = runTest {
        // Given
        val workoutId = 1L
        coEvery { workoutDao.getWorkoutById(workoutId) } returns testWorkoutEntity
        every { mapper.toDomain(testWorkoutEntity) } returns testWorkout

        // When
        val result = repository.getWorkoutById(workoutId)

        // Then
        assertTrue(result.isSuccess)
        assertEquals(testWorkout, result.getOrNull())
        coVerify { workoutDao.getWorkoutById(workoutId) }
    }

    @Test
    fun `getWorkoutById should return error when workout not found`() = runTest {
        // Given
        val workoutId = 999L
        coEvery { workoutDao.getWorkoutById(workoutId) } returns null

        // When
        val result = repository.getWorkoutById(workoutId)

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftHelperError.DatabaseError)
        assertTrue(error?.message?.contains("not found") == true)
    }

    @Test
    fun `deleteWorkout should return success when workout exists`() = runTest {
        // Given
        val workoutId = 1L
        coEvery { workoutDao.getWorkoutById(workoutId) } returns testWorkoutEntity
        coEvery { workoutDao.deleteWorkout(testWorkoutEntity) } returns Unit

        // When
        val result = repository.deleteWorkout(workoutId)

        // Then
        assertTrue(result.isSuccess)
        coVerify { workoutDao.getWorkoutById(workoutId) }
        coVerify { workoutDao.deleteWorkout(testWorkoutEntity) }
    }

    @Test
    fun `deleteWorkout should return error when workout not found`() = runTest {
        // Given
        val workoutId = 999L
        coEvery { workoutDao.getWorkoutById(workoutId) } returns null

        // When
        val result = repository.deleteWorkout(workoutId)

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftHelperError.DatabaseError)
        assertTrue(error?.message?.contains("not found") == true)
    }

    @Test
    fun `deleteWorkout should return error when delete fails`() = runTest {
        // Given
        val workoutId = 1L
        val exception = RuntimeException("Delete failed")
        coEvery { workoutDao.getWorkoutById(workoutId) } returns testWorkoutEntity
        coEvery { workoutDao.deleteWorkout(testWorkoutEntity) } throws exception

        // When
        val result = repository.deleteWorkout(workoutId)

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftHelperError.DatabaseError)
    }
}
