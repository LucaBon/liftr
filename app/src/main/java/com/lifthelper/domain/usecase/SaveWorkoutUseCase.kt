package com.lifthelper.domain.usecase

import com.lifthelper.domain.error.LiftHelperError
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.Workout
import com.lifthelper.domain.repository.WorkoutRepository
import com.lifthelper.domain.util.onError
import javax.inject.Inject

/**
 * Use case for saving a workout.
 *
 * Validates workout data and persists it to the repository.
 */
class SaveWorkoutUseCase @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val logger: Logger
) : UseCase<SaveWorkoutUseCase.Params, Result<Long>>() {

    companion object {
        private const val TAG = "SaveWorkoutUseCase"
    }

    /**
     * Saves a workout to the repository.
     *
     * @param params Parameters containing the workout to save
     * @return Result containing the workout ID on success, or an error
     */
    override suspend fun invoke(params: Params): Result<Long> {
        // Validate workout data
        if (params.workout.sets.isEmpty()) {
            val error = LiftHelperError.ValidationError(
                "sets",
                "Workout must contain at least one set"
            )
            logger.w(TAG, "Validation failed: ${error.message}")
            return Result.failure(error)
        }

        // Save to repository
        return workoutRepository.saveWorkout(params.workout)
            .onError { error ->
                logger.e(TAG, "Failed to save workout: ${error.message}", error)
            }
    }

    /**
     * Parameters for saving a workout.
     *
     * @property workout The workout to save
     */
    data class Params(
        val workout: Workout
    )
}
