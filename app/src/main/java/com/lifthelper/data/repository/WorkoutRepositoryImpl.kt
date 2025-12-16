package com.lifthelper.data.repository

import com.lifthelper.data.local.database.dao.WorkoutDao
import com.lifthelper.data.mapper.WorkoutMapper
import com.lifthelper.domain.error.LiftHelperError
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.Workout
import com.lifthelper.domain.repository.WorkoutRepository
import com.lifthelper.domain.util.mapError
import com.lifthelper.domain.util.onError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.sql.SQLException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of WorkoutRepository.
 *
 * Provides data access for workout operations using Room database.
 * Handles entity/domain conversion and error handling with Result<T>.
 */
@Singleton
class WorkoutRepositoryImpl @Inject constructor(
    private val workoutDao: WorkoutDao,
    private val mapper: WorkoutMapper,
    private val logger: Logger
) : WorkoutRepository {

    companion object {
        private const val TAG = "WorkoutRepository"
    }

    override suspend fun saveWorkout(workout: Workout): Result<Long> = runCatching {
        val entity = mapper.toEntity(workout)
        val workoutId = workoutDao.insertWorkout(entity)
        logger.d(TAG, "Workout saved successfully with ID: $workoutId")
        workoutId
    }.mapError { error ->
        when (error) {
            is SQLException -> LiftHelperError.DatabaseError(
                "Failed to save workout",
                error
            )
            is LiftHelperError.ValidationError -> error
            else -> LiftHelperError.DatabaseError(
                "Unexpected error saving workout",
                error
            )
        }
    }.onError { error ->
        logger.e(TAG, "Save failed: ${error.message}", error)
    }

    override fun getWorkouts(): Flow<List<Workout>> {
        return workoutDao.getAllWorkouts()
            .map { entities ->
                mapper.toDomainList(entities)
            }
    }

    override suspend fun getWorkoutById(id: Long): Result<Workout> = runCatching {
        val entity = workoutDao.getWorkoutById(id)
        if (entity != null) {
            mapper.toDomain(entity)
        } else {
            throw LiftHelperError.DatabaseError("Workout with ID $id not found")
        }
    }.mapError { error ->
        when (error) {
            is LiftHelperError -> error
            is SQLException -> LiftHelperError.DatabaseError(
                "Failed to get workout with ID: $id",
                error
            )
            else -> LiftHelperError.DatabaseError(
                "Unexpected error getting workout with ID: $id",
                error
            )
        }
    }.onError { error ->
        logger.e(TAG, "Failed to get workout: ${error.message}", error)
    }

    override suspend fun deleteWorkout(id: Long): Result<Unit> = runCatching {
        val entity = workoutDao.getWorkoutById(id)
        if (entity != null) {
            workoutDao.deleteWorkout(entity)
            logger.d(TAG, "Workout deleted successfully with ID: $id")
            Unit
        } else {
            throw LiftHelperError.DatabaseError("Workout with ID $id not found")
        }
    }.mapError { error ->
        when (error) {
            is LiftHelperError -> error
            is SQLException -> LiftHelperError.DatabaseError(
                "Failed to delete workout with ID: $id",
                error
            )
            else -> LiftHelperError.DatabaseError(
                "Unexpected error deleting workout with ID: $id",
                error
            )
        }
    }.onError { error ->
        logger.e(TAG, "Delete failed: ${error.message}", error)
    }
}
