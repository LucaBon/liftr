package com.lifthelper.data.repository

import androidx.room.withTransaction
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
import com.lifthelper.domain.repository.WorkoutRepository
import com.lifthelper.domain.util.mapError
import com.lifthelper.domain.util.onError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.sql.SQLException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkoutRepositoryImpl @Inject constructor(
    private val database: LiftDatabase,
    private val workoutDao: WorkoutDao,
    private val setDao: SetDao,
    private val repDao: RepDao,
    private val mapper: WorkoutMapper,
    private val setMapper: SetMapper,
    private val repMapper: RepMapper,
    private val logger: Logger
) : WorkoutRepository {

    companion object {
        private const val TAG = "WorkoutRepository"
    }

    override suspend fun saveWorkout(workout: Workout): Result<Long> = runCatching {
        database.withTransaction {
            val workoutEntity = mapper.toEntity(workout)
            val workoutId = workoutDao.insertWorkout(workoutEntity)

            workout.sets.forEach { set ->
                val setEntity = setMapper.toEntity(set).copy(workoutId = workoutId)
                val setId = setDao.insertSet(setEntity)

                val repEntities = set.reps.map { rep ->
                    repMapper.toEntity(rep).copy(setId = setId)
                }
                if (repEntities.isNotEmpty()) {
                    repDao.insertReps(repEntities)
                }
            }

            logger.d(TAG, "Workout saved: id=$workoutId, sets=${workout.sets.size}, " +
                "totalReps=${workout.sets.sumOf { it.reps.size }}")
            workoutId
        }
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
            .map { workoutEntities ->
                workoutEntities.map { loadWorkoutWithSetsAndReps(it) }
            }
    }

    override suspend fun getWorkoutById(id: Long): Result<Workout> = runCatching {
        val entity = workoutDao.getWorkoutById(id)
            ?: throw LiftHelperError.DatabaseError("Workout with ID $id not found")
        loadWorkoutWithSetsAndReps(entity)
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

    private suspend fun loadWorkoutWithSetsAndReps(workoutEntity: WorkoutEntity): Workout {
        val setEntities = setDao.getSetsForWorkoutSync(workoutEntity.id)
        val sets = setEntities.map { setEntity ->
            val repEntities = repDao.getRepsForSetSync(setEntity.id)
            val reps = repEntities.map { repMapper.toDomain(it) }
            setMapper.toDomain(setEntity, reps)
        }
        return mapper.toDomain(workoutEntity, sets)
    }
}
