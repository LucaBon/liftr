package com.liftr.domain.exercise

import com.liftr.domain.exercise.strategy.BackSquatStrategy
import com.liftr.domain.exercise.strategy.BenchPressStrategy
import com.liftr.domain.exercise.strategy.CleanStrategy
import com.liftr.domain.exercise.strategy.DeadliftStrategy
import com.liftr.domain.exercise.strategy.FrontSquatStrategy
import com.liftr.domain.exercise.strategy.JumpStrategy
import com.liftr.domain.exercise.strategy.SnatchStrategy
import com.liftr.domain.model.ExerciseType

/**
 * Factory for creating exercise strategy instances.
 *
 * This factory provides the appropriate strategy implementation based on
 * the exercise type. It enables the Strategy pattern by encapsulating
 * exercise-specific logic in dedicated classes.
 *
 * Built by hand in the DI module: the two jump strategies share a type, so the
 * constructor can't be resolved by type alone.
 */
class ExerciseStrategyFactory(
    private val backSquatStrategy: BackSquatStrategy,
    private val frontSquatStrategy: FrontSquatStrategy,
    private val benchPressStrategy: BenchPressStrategy,
    private val deadliftStrategy: DeadliftStrategy,
    private val cleanStrategy: CleanStrategy,
    private val snatchStrategy: SnatchStrategy,
    private val verticalJumpStrategy: JumpStrategy,
    private val loadedJumpStrategy: JumpStrategy
) {
    init {
        require(verticalJumpStrategy.exerciseType == ExerciseType.VERTICAL_JUMP)
        require(loadedJumpStrategy.exerciseType == ExerciseType.LOADED_JUMP)
    }

    /**
     * Gets the appropriate strategy for the given exercise type.
     *
     * @param exerciseType The type of exercise
     * @return The strategy implementation for that exercise
     */
    fun getStrategy(exerciseType: ExerciseType): ExerciseStrategy {
        return when (exerciseType) {
            ExerciseType.BACK_SQUAT -> backSquatStrategy
            ExerciseType.FRONT_SQUAT -> frontSquatStrategy
            ExerciseType.BENCH_PRESS -> benchPressStrategy
            ExerciseType.DEADLIFT -> deadliftStrategy
            ExerciseType.CLEAN -> cleanStrategy
            ExerciseType.SNATCH -> snatchStrategy
            ExerciseType.VERTICAL_JUMP -> verticalJumpStrategy
            ExerciseType.LOADED_JUMP -> loadedJumpStrategy
            // No else: a new ExerciseType must get a strategy to compile.
        }
    }

    /**
     * Gets all available exercise strategies.
     *
     * @return List of all implemented strategies
     */
    fun getAllStrategies(): List<ExerciseStrategy> {
        return listOf(
            backSquatStrategy, frontSquatStrategy, benchPressStrategy, deadliftStrategy,
            cleanStrategy, snatchStrategy, verticalJumpStrategy, loadedJumpStrategy
        )
    }

    /**
     * Gets the list of supported exercise types.
     *
     * @return List of exercise types that have strategies implemented
     */
    fun getSupportedExercises(): List<ExerciseType> {
        return getAllStrategies().map { it.exerciseType }
    }
}
