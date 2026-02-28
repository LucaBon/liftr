package com.lifthelper.di

import com.lifthelper.domain.exercise.ExerciseStrategyFactory
import com.lifthelper.domain.exercise.strategy.BackSquatStrategy
import com.lifthelper.domain.exercise.strategy.BenchPressStrategy
import com.lifthelper.domain.exercise.strategy.DeadliftStrategy
import com.lifthelper.domain.exercise.strategy.FrontSquatStrategy
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.vision.IPositionCalculator
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
     * Provides ExerciseStrategyFactory.
     */
    @Provides
    @Singleton
    fun provideExerciseStrategyFactory(
        backSquatStrategy: BackSquatStrategy,
        frontSquatStrategy: FrontSquatStrategy,
        benchPressStrategy: BenchPressStrategy,
        deadliftStrategy: DeadliftStrategy
    ): ExerciseStrategyFactory {
        return ExerciseStrategyFactory(
            backSquatStrategy = backSquatStrategy,
            frontSquatStrategy = frontSquatStrategy,
            benchPressStrategy = benchPressStrategy,
            deadliftStrategy = deadliftStrategy
        )
    }
}
