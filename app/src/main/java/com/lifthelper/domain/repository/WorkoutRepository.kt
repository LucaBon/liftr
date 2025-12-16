package com.lifthelper.domain.repository

import com.lifthelper.domain.model.Workout
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for workout data operations.
 *
 * Provides an abstraction layer between the data sources and the domain/presentation layers.
 * All operations return Result<T> for error handling and Flow<T> for reactive data streams.
 */
interface WorkoutRepository {
    /**
     * Saves a workout to the data source.
     *
     * @param workout The workout to save
     * @return Result containing the generated workout ID on success, or an error
     */
    suspend fun saveWorkout(workout: Workout): Result<Long>

    /**
     * Retrieves all workouts as a reactive stream.
     *
     * @return Flow emitting the list of all workouts, ordered by date descending
     */
    fun getWorkouts(): Flow<List<Workout>>

    /**
     * Retrieves a specific workout by its ID.
     *
     * @param id The unique identifier of the workout
     * @return Result containing the workout if found, or an error
     */
    suspend fun getWorkoutById(id: Long): Result<Workout>

    /**
     * Deletes a workout from the data source.
     *
     * @param id The unique identifier of the workout to delete
     * @return Result indicating success or failure
     */
    suspend fun deleteWorkout(id: Long): Result<Unit>
}
