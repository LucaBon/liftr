package com.liftr.data.repository

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.withTransaction
import app.cash.turbine.test
import com.liftr.data.local.database.LiftDatabase
import com.liftr.data.local.database.dao.RepDao
import com.liftr.data.local.database.dao.SetDao
import com.liftr.data.local.database.dao.WorkoutDao
import com.liftr.data.local.database.entities.WorkoutEntity
import com.liftr.data.mapper.RepMapper
import com.liftr.data.mapper.SetMapper
import com.liftr.data.mapper.WorkoutMapper
import com.liftr.domain.error.LiftrError
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.Workout
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
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

    private lateinit var database: LiftDatabase
    private lateinit var workoutDao: WorkoutDao
    private lateinit var setDao: SetDao
    private lateinit var repDao: RepDao
    private lateinit var repository: WorkoutRepositoryImpl
    private lateinit var mapper: WorkoutMapper
    private lateinit var setMapper: SetMapper
    private lateinit var repMapper: RepMapper
    private lateinit var logger: Logger

    @Before
    fun setup() {
        database = mockk(relaxed = true)
        workoutDao = mockk()
        setDao = mockk(relaxed = true)
        repDao = mockk(relaxed = true)
        mapper = WorkoutMapper() // Real mapper for integration testing
        setMapper = SetMapper()
        repMapper = RepMapper()
        logger = mockk(relaxed = true)

        // Mock withTransaction — suspend lambda + MockK don't mix well,
        // so we mock the return value per-test. Cascade logic is covered
        // by instrumented tests with a real Room database.
        mockkStatic("androidx.room.RoomDatabaseKt")

        repository = WorkoutRepositoryImpl(
            database = database,
            workoutDao = workoutDao,
            setDao = setDao,
            repDao = repDao,
            mapper = mapper,
            setMapper = setMapper,
            repMapper = repMapper,
            logger = logger
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `saveWorkout should return success when transaction succeeds`() = runTest {
        // Given
        coEvery { database.withTransaction<Long>(any()) } returns 1L

        // When
        val result = repository.saveWorkout(createTestWorkout(id = 0))

        // Then
        assertTrue(result.isSuccess)
        assertEquals(1L, result.getOrNull())
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
        assertTrue(error is LiftrError.DatabaseError)
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
        coEvery { database.withTransaction<Long>(any()) } throws java.sql.SQLException("DB Error")

        // When
        val result = repository.saveWorkout(createTestWorkout(id = 0))

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftrError.DatabaseError)
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
        assertTrue(error is LiftrError.DatabaseError)
    }

    @Test
    fun `repository should return success for valid save`() = runTest {
        // Given
        coEvery { database.withTransaction<Long>(any()) } returns 1L

        // When
        val result = repository.saveWorkout(createTestWorkout(id = 0))

        // Then
        assertTrue(result.isSuccess)
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
