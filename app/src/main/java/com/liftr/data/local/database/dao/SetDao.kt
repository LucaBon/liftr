package com.liftr.data.local.database.dao

import androidx.room.*
import com.liftr.data.local.database.entities.SetEntity
import com.liftr.domain.model.ExerciseType
import kotlinx.coroutines.flow.Flow

@Dao
interface SetDao {
    @Insert
    suspend fun insertSet(set: SetEntity): Long

    @Query("SELECT * FROM sets WHERE workoutId = :workoutId")
    fun getSetsForWorkout(workoutId: Long): Flow<List<SetEntity>>

    @Query("SELECT * FROM sets WHERE workoutId = :workoutId ORDER BY timestamp ASC")
    suspend fun getSetsForWorkoutSync(workoutId: Long): List<SetEntity>

    @Query("""
        SELECT * FROM sets
        WHERE exerciseType = :exerciseType AND isManual = 0
        ORDER BY timestamp DESC LIMIT :limit
    """)
    fun getRecentSetsForExercise(exerciseType: ExerciseType, limit: Int): Flow<List<SetEntity>>

    @Update
    suspend fun updateSet(set: SetEntity)

    // Analytics queries
    //
    // All of these are keyed by exerciseType, so they must exclude manual sets:
    // manually logged accessory work is stored with a placeholder exerciseType
    // (see LogManualSetUseCase) and zero velocities. Including it would credit
    // accessory lifts as PRs for the placeholder exercise and feed 0 m/s points
    // into the load-velocity regression.

    @Query("""
        SELECT * FROM sets
        WHERE exerciseType = :exerciseType AND isManual = 0 AND timestamp >= :fromTimestamp
        ORDER BY timestamp ASC
    """)
    suspend fun getSetsForExerciseInRange(exerciseType: ExerciseType, fromTimestamp: Long): List<SetEntity>

    @Query("SELECT * FROM sets WHERE isManual = 0 AND timestamp >= :fromTimestamp ORDER BY timestamp ASC")
    suspend fun getAllSetsInRange(fromTimestamp: Long): List<SetEntity>

    @Query("""
        SELECT * FROM sets
        WHERE exerciseType = :exerciseType AND isManual = 0
        ORDER BY weight DESC LIMIT 1
    """)
    suspend fun getHeaviestSet(exerciseType: ExerciseType): SetEntity?

    @Query("""
        SELECT * FROM sets
        WHERE exerciseType = :exerciseType AND isManual = 0
        ORDER BY (weight * completedReps) DESC LIMIT 1
    """)
    suspend fun getHighestVolumeSet(exerciseType: ExerciseType): SetEntity?

    @Query("""
        SELECT DISTINCT weight FROM sets
        WHERE exerciseType = :exerciseType AND isManual = 0
        ORDER BY weight ASC
    """)
    suspend fun getDistinctWeights(exerciseType: ExerciseType): List<Float>
}
