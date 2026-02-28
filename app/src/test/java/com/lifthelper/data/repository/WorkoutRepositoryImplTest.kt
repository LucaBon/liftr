package com.lifthelper.data.repository

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.withTransaction
import app.cash.turbine.test
import com.lifthelper.data.local.database.LiftDatabase
import com.lifthelper.data.local.database.dao.RepDao
import com.lifthelper.data.local.database.dao.SetDao
import com.lifthelper.data.local.database.dao.WorkoutDao
import com.lifthelper.data.local.database.entities.WorkoutEntity
import com.lifthelper.data.mapper.RepMapper
import com.lifthelper.data.mapper.SetMapper
import com.lifthelper.data.mapper.WorkoutMapper
import com.lifthelper.domain.error.LiftHelperError
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.Workout
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
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

    private lateinit var database: LiftDatabase
    private lateinit var workoutDao: WorkoutDao
    private lateinit var setDao: SetDao
    private lateinit var repDao: RepDao
    private lateinit var mapper: WorkoutMapper
    private lateinit var setMapper: SetMapper
    private lateinit var repMapper: RepMapper
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
        database = mockk(relaxed = true)
        workoutDao = mockk()
        setDao = mockk(relaxed = true)
        repDao = mockk(relaxed = true)
        mapper = mockk()
        setMapper = mockk(relaxed = true)
        repMapper = mockk(relaxed = true)
        logger = mockk(relaxed = true)

        // Mock withTransaction — suspend lambda + MockK don't mix well,
        // so we mock the return value directly. Cascade logic is covered
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
    fun `saveWorkout should return success with workout ID when save succeeds`() = runTest {
        // Given
        val expectedId = 1L
        coEvery { database.withTransaction<Long>(any()) } returns expectedId

        // When
        val result = repository.saveWorkout(testWorkout)

        // Then
        assertTrue(result.isSuccess)
        assertEquals(expectedId, result.getOrNull())
    }

    @Test
    fun `saveWorkout should return database error when DAO throws exception`() = runTest {
        // Given
        coEvery { database.withTransaction<Long>(any()) } throws RuntimeException("Database error")

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
        every { workoutDao.getAllWorkouts() } returns flowOf(entities)
        coEvery { setDao.getSetsForWorkoutSync(testWorkoutEntity.id) } returns emptyList()
        every { mapper.toDomain(testWorkoutEntity, emptyList()) } returns testWorkout

        // When
        repository.getWorkouts().test {
            // Then
            val emittedWorkouts = awaitItem()
            assertEquals(1, emittedWorkouts.size)
            assertEquals(testWorkout.id, emittedWorkouts[0].id)
            awaitComplete()
        }

        verify { workoutDao.getAllWorkouts() }
    }

    @Test
    fun `getWorkouts should return empty list when no workouts exist`() = runTest {
        // Given
        every { workoutDao.getAllWorkouts() } returns flowOf(emptyList())

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
        coEvery { setDao.getSetsForWorkoutSync(testWorkoutEntity.id) } returns emptyList()
        every { mapper.toDomain(testWorkoutEntity, emptyList()) } returns testWorkout

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
