package com.lifthelper.data.local.database.dao

import androidx.room.*
import com.lifthelper.data.local.database.entities.SetEntity
import com.lifthelper.domain.model.ExerciseType
import kotlinx.coroutines.flow.Flow

@Dao
interface SetDao {
    @Insert
    suspend fun insertSet(set: SetEntity): Long

    @Query("SELECT * FROM sets WHERE workoutId = :workoutId")
    fun getSetsForWorkout(workoutId: Long): Flow<List<SetEntity>>

    @Query("SELECT * FROM sets WHERE workoutId = :workoutId ORDER BY timestamp ASC")
    suspend fun getSetsForWorkoutSync(workoutId: Long): List<SetEntity>

    @Query("SELECT * FROM sets WHERE exerciseType = :exerciseType ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentSetsForExercise(exerciseType: ExerciseType, limit: Int): Flow<List<SetEntity>>

    @Update
    suspend fun updateSet(set: SetEntity)

    // Analytics queries

    @Query("""
        SELECT * FROM sets
        WHERE exerciseType = :exerciseType AND timestamp >= :fromTimestamp
        ORDER BY timestamp ASC
    """)
    suspend fun getSetsForExerciseInRange(exerciseType: ExerciseType, fromTimestamp: Long): List<SetEntity>

    @Query("SELECT * FROM sets WHERE timestamp >= :fromTimestamp ORDER BY timestamp ASC")
    suspend fun getAllSetsInRange(fromTimestamp: Long): List<SetEntity>

    @Query("SELECT * FROM sets WHERE exerciseType = :exerciseType ORDER BY weight DESC LIMIT 1")
    suspend fun getHeaviestSet(exerciseType: ExerciseType): SetEntity?

    @Query("SELECT * FROM sets WHERE exerciseType = :exerciseType ORDER BY (weight * completedReps) DESC LIMIT 1")
    suspend fun getHighestVolumeSet(exerciseType: ExerciseType): SetEntity?

    @Query("SELECT DISTINCT weight FROM sets WHERE exerciseType = :exerciseType ORDER BY weight ASC")
    suspend fun getDistinctWeights(exerciseType: ExerciseType): List<Float>
}
