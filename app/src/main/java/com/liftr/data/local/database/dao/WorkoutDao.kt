package com.liftr.data.local.database.dao

import androidx.room.*
import com.liftr.data.local.database.entities.WorkoutEntity
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

    // Analytics queries

    @Query("SELECT COUNT(*) FROM workouts")
    suspend fun getWorkoutCount(): Int

    @Query("SELECT * FROM workouts ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentWorkouts(limit: Int): List<WorkoutEntity>
}
