package com.lifthelper.domain.exercise

import com.lifthelper.domain.exercise.strategy.BackSquatStrategy
import com.lifthelper.domain.exercise.strategy.FrontSquatStrategy
import com.lifthelper.domain.model.ExerciseType
import javax.inject.Inject

/**
 * Factory for creating exercise strategy instances.
 *
 * This factory provides the appropriate strategy implementation based on
 * the exercise type. It enables the Strategy pattern by encapsulating
 * exercise-specific logic in dedicated classes.
 */
class ExerciseStrategyFactory @Inject constructor(
    private val backSquatStrategy: BackSquatStrategy,
    private val frontSquatStrategy: FrontSquatStrategy
    // Add more strategies as they are implemented
) {
    /**
     * Gets the appropriate strategy for the given exercise type.
     *
     * @param exerciseType The type of exercise
     * @return The strategy implementation for that exercise
     * @throws UnsupportedExerciseException if the exercise type is not yet implemented
     */
    fun getStrategy(exerciseType: ExerciseType): ExerciseStrategy {
        return when (exerciseType) {
            ExerciseType.BACK_SQUAT -> backSquatStrategy
            ExerciseType.FRONT_SQUAT -> frontSquatStrategy
            else -> throw UnsupportedExerciseException(
                "Exercise $exerciseType not yet implemented. " +
                "Supported exercises: ${getSupportedExercises().joinToString { it.getDisplayName() }}"
            )
        }
    }

    /**
     * Gets all available exercise strategies.
     *
     * @return List of all implemented strategies
     */
    fun getAllStrategies(): List<ExerciseStrategy> {
        return listOf(backSquatStrategy, frontSquatStrategy)
    }

    /**
     * Gets the list of supported exercise types.
     *
     * @return List of exercise types that have strategies implemented
     */
    fun getSupportedExercises(): List<ExerciseType> {
        return getAllStrategies().map { it.exerciseType }
    }

    /**
     * Checks if a given exercise type is supported.
     *
     * @param exerciseType The exercise type to check
     * @return true if the exercise has a strategy implementation, false otherwise
     */
    fun isSupported(exerciseType: ExerciseType): Boolean {
        return try {
            getStrategy(exerciseType)
            true
        } catch (e: UnsupportedExerciseException) {
            false
        }
    }
}

/**
 * Exception thrown when attempting to use an exercise type that doesn't
 * have a strategy implementation yet.
 */
class UnsupportedExerciseException(message: String) : Exception(message)
