package com.liftr.di

import com.liftr.domain.exercise.ExerciseStrategyFactory
import com.liftr.domain.exercise.strategy.BackSquatStrategy
import com.liftr.domain.exercise.strategy.BenchPressStrategy
import com.liftr.domain.exercise.strategy.CleanStrategy
import com.liftr.domain.exercise.strategy.DeadliftStrategy
import com.liftr.domain.exercise.strategy.FrontSquatStrategy
import com.liftr.domain.exercise.strategy.JumpStrategy
import com.liftr.domain.exercise.strategy.SnatchStrategy
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.vision.IPositionCalculator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dagger Hilt module for providing exercise strategy dependencies.
 *
 * Exercise strategies encapsulate exercise-specific logic for phase detection,
 * form validation, and metrics calculation.
 */
@Module
@InstallIn(SingletonComponent::class)
object ExerciseModule {

    /**
     * Provides BackSquatStrategy.
     */
    @Provides
    @Singleton
    fun provideBackSquatStrategy(
        positionCalculator: IPositionCalculator,
        logger: Logger
    ): BackSquatStrategy {
        return BackSquatStrategy(positionCalculator, logger)
    }

    /**
     * Provides FrontSquatStrategy.
     */
    @Provides
    @Singleton
    fun provideFrontSquatStrategy(
        positionCalculator: IPositionCalculator,
        logger: Logger
    ): FrontSquatStrategy {
        return FrontSquatStrategy(positionCalculator, logger)
    }

    /**
     * Provides BenchPressStrategy.
     */
    @Provides
    @Singleton
    fun provideBenchPressStrategy(
        positionCalculator: IPositionCalculator,
        logger: Logger
    ): BenchPressStrategy {
        return BenchPressStrategy(positionCalculator, logger)
    }

    /**
     * Provides DeadliftStrategy.
     */
    @Provides
    @Singleton
    fun provideDeadliftStrategy(
        positionCalculator: IPositionCalculator,
        logger: Logger
    ): DeadliftStrategy {
        return DeadliftStrategy(positionCalculator, logger)
    }

    /**
     * Provides CleanStrategy.
     */
    @Provides
    @Singleton
    fun provideCleanStrategy(
        positionCalculator: IPositionCalculator,
        logger: Logger
    ): CleanStrategy {
        return CleanStrategy(positionCalculator, logger)
    }

    /**
     * Provides SnatchStrategy.
     */
    @Provides
    @Singleton
    fun provideSnatchStrategy(
        positionCalculator: IPositionCalculator,
        logger: Logger
    ): SnatchStrategy {
        return SnatchStrategy(positionCalculator, logger)
    }

    /**
     * Provides ExerciseStrategyFactory.
     */
    @Provides
    @Singleton
    fun provideExerciseStrategyFactory(
        backSquatStrategy: BackSquatStrategy,
        frontSquatStrategy: FrontSquatStrategy,
        benchPressStrategy: BenchPressStrategy,
        deadliftStrategy: DeadliftStrategy,
        cleanStrategy: CleanStrategy,
        snatchStrategy: SnatchStrategy,
        logger: Logger
    ): ExerciseStrategyFactory {
        return ExerciseStrategyFactory(
            backSquatStrategy = backSquatStrategy,
            frontSquatStrategy = frontSquatStrategy,
            benchPressStrategy = benchPressStrategy,
            deadliftStrategy = deadliftStrategy,
            cleanStrategy = cleanStrategy,
            snatchStrategy = snatchStrategy,
            verticalJumpStrategy = JumpStrategy(ExerciseType.VERTICAL_JUMP, logger),
            loadedJumpStrategy = JumpStrategy(ExerciseType.LOADED_JUMP, logger)
        )
    }
}
