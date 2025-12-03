package com.lifthelper.data.local.database.dao

import androidx.room.*
import com.lifthelper.data.local.database.entities.WorkoutEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    @Insert
    suspend fun insertWorkout(workout: WorkoutEntity): Long

    @Query("SELECT * FROM workouts ORDER BY date DESC")
    fun getAllWorkouts(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE id = :workoutId")
    suspend fun getWorkoutById(workoutId: Long): WorkoutEntity?

    @Delete
    suspend fun deleteWorkout(workout: WorkoutEntity)
}
