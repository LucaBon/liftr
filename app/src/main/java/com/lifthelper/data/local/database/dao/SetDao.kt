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

    @Query("SELECT * FROM sets WHERE exerciseType = :exerciseType ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentSetsForExercise(exerciseType: ExerciseType, limit: Int): Flow<List<SetEntity>>

    @Update
    suspend fun updateSet(set: SetEntity)
}
