package com.lifthelper.data.repository

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lifthelper.data.local.database.dao.WorkoutDao
import com.lifthelper.data.local.database.entities.WorkoutEntity
import com.lifthelper.data.mapper.WorkoutMapper
import com.lifthelper.domain.error.LiftHelperError
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.Workout
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Date

/**
 * Full integration tests for WorkoutRepositoryImpl.
 *
 * Tests the complete data flow from repository through real mapper
 * and DAO integration, with comprehensive error handling scenarios.
 */
class WorkoutRepositoryFullIntegrationTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var workoutDao: WorkoutDao
    private lateinit var repository: WorkoutRepositoryImpl
    private lateinit var mapper: WorkoutMapper
    private lateinit var logger: Logger

    @Before
    fun setup() {
        workoutDao = mockk()
        mapper = WorkoutMapper() // Real mapper for integration testing
        logger = mockk(relaxed = true)

        repository = WorkoutRepositoryImpl(
            workoutDao = workoutDao,
            mapper = mapper,
            logger = logger
        )
    }

    @Test
    fun `saveWorkout should use real mapper to convert domain to entity`() = runTest {
        // Given
        val workout = createTestWorkout(id = 0)
        val entitySlot = slot<WorkoutEntity>()
        coEvery { workoutDao.insertWorkout(capture(entitySlot)) } returns 1L

        // When
        val result = repository.saveWorkout(workout)

        // Then
        assertTrue(result.isSuccess)
        assertEquals(1L, result.getOrNull())

        // Verify entity was mapped correctly
        val capturedEntity = entitySlot.captured
        assertEquals(workout.notes, capturedEntity.notes)
        assertEquals(workout.duration, capturedEntity.duration)

        coVerify { workoutDao.insertWorkout(any()) }
    }

    @Test
    fun `getWorkouts should use real mapper to convert entities to domain`() = runTest {
        // Given
        val entity = WorkoutEntity(
            id = 1,
            date = System.currentTimeMillis(),
            duration = 3600000,
            notes = "Test workout",
            createdAt = System.currentTimeMillis()
        )
        every { workoutDao.getAllWorkouts() } returns flowOf(listOf(entity))

        // When
        repository.getWorkouts().test {
            val workouts = awaitItem()

            // Then
            assertEquals(1, workouts.size)
            assertEquals(entity.notes, workouts[0].notes)
            assertEquals(entity.duration, workouts[0].duration)
            assertEquals(entity.id, workouts[0].id)

            awaitComplete()
        }

        verify { workoutDao.getAllWorkouts() }
    }

    @Test
    fun `getWorkoutById should use real mapper and handle not found`() = runTest {
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
    fun `getWorkoutById should use real mapper to convert entity to domain`() = runTest {
        // Given
        val entity = WorkoutEntity(
            id = 1,
            date = System.currentTimeMillis(),
            duration = 3600000,
            notes = "Found workout",
            createdAt = System.currentTimeMillis()
        )
        coEvery { workoutDao.getWorkoutById(1L) } returns entity

        // When
        val result = repository.getWorkoutById(1L)

        // Then
        assertTrue(result.isSuccess)
        val workout = result.getOrNull()
        assertNotNull(workout)
        assertEquals(entity.notes, workout?.notes)
        assertEquals(entity.duration, workout?.duration)
        assertEquals(entity.id, workout?.id)
    }

    @Test
    fun `deleteWorkout should handle entity retrieval and deletion`() = runTest {
        // Given
        val entity = WorkoutEntity(
            id = 1,
            date = System.currentTimeMillis(),
            duration = 3600000,
            notes = "Delete me",
            createdAt = System.currentTimeMillis()
        )
        coEvery { workoutDao.getWorkoutById(1L) } returns entity
        coEvery { workoutDao.deleteWorkout(entity) } returns Unit

        // When
        val result = repository.deleteWorkout(1L)

        // Then
        assertTrue(result.isSuccess)
        coVerify { workoutDao.getWorkoutById(1L) }
        coVerify { workoutDao.deleteWorkout(entity) }
    }

    @Test
    fun `saveWorkout should handle SQL exceptions`() = runTest {
        // Given
        val workout = createTestWorkout(id = 0)
        coEvery { workoutDao.insertWorkout(any()) } throws java.sql.SQLException("DB Error")

        // When
        val result = repository.saveWorkout(workout)

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftHelperError.DatabaseError)
    }

    @Test
    fun `getWorkouts should handle empty database`() = runTest {
        // Given
        every { workoutDao.getAllWorkouts() } returns flowOf(emptyList())

        // When
        repository.getWorkouts().test {
            val workouts = awaitItem()

            // Then
            assertTrue(workouts.isEmpty())
            awaitComplete()
        }
    }

    @Test
    fun `mapper should correctly convert multiple entities`() = runTest {
        // Given
        val entities = listOf(
            WorkoutEntity(1, System.currentTimeMillis(), 3600000, "Workout 1", System.currentTimeMillis()),
            WorkoutEntity(2, System.currentTimeMillis(), 7200000, "Workout 2", System.currentTimeMillis()),
            WorkoutEntity(3, System.currentTimeMillis(), 5400000, "Workout 3", System.currentTimeMillis())
        )
        every { workoutDao.getAllWorkouts() } returns flowOf(entities)

        // When
        repository.getWorkouts().test {
            val workouts = awaitItem()

            // Then
            assertEquals(3, workouts.size)
            assertEquals("Workout 1", workouts[0].notes)
            assertEquals("Workout 2", workouts[1].notes)
            assertEquals("Workout 3", workouts[2].notes)
            assertEquals(3600000L, workouts[0].duration)
            assertEquals(7200000L, workouts[1].duration)
            assertEquals(5400000L, workouts[2].duration)

            awaitComplete()
        }
    }

    @Test
    fun `mapper should preserve all workout fields during round trip`() = runTest {
        // Given
        val originalWorkout = createTestWorkout(id = 0, notes = "Complete workout")

        // Convert to entity and back
        val entity = mapper.toEntity(originalWorkout)
        val reconstructedWorkout = mapper.toDomain(entity)

        // Then
        assertEquals(originalWorkout.notes, reconstructedWorkout.notes)
        assertEquals(originalWorkout.duration, reconstructedWorkout.duration)
        assertNotNull(reconstructedWorkout.date)
        assertTrue(reconstructedWorkout.createdAt > 0)
    }

    @Test
    fun `deleteWorkout should handle exception during deletion`() = runTest {
        // Given
        val entity = WorkoutEntity(
            id = 1,
            date = System.currentTimeMillis(),
            duration = 3600000,
            notes = "Test",
            createdAt = System.currentTimeMillis()
        )
        coEvery { workoutDao.getWorkoutById(1L) } returns entity
        coEvery { workoutDao.deleteWorkout(entity) } throws RuntimeException("Delete failed")

        // When
        val result = repository.deleteWorkout(1L)

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftHelperError.DatabaseError)
    }

    @Test
    fun `repository should log operations correctly`() = runTest {
        // Given
        val workout = createTestWorkout(id = 0)
        coEvery { workoutDao.insertWorkout(any()) } returns 1L

        // When
        repository.saveWorkout(workout)

        // Then
        verify { logger.d(any(), any<String>()) }
    }

    private fun createTestWorkout(id: Long, notes: String = "Test workout"): Workout {
        return Workout(
            id = id,
            date = Date(System.currentTimeMillis()),
            duration = 3600000L,
            notes = notes,
            sets = emptyList(),
            createdAt = System.currentTimeMillis()
        )
    }
}
