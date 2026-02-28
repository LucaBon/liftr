package com.lifthelper.di

import com.lifthelper.domain.exercise.ExerciseStrategyFactory
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.repository.AnalyticsRepository
import com.lifthelper.domain.repository.CalibrationRepository
import com.lifthelper.domain.repository.SettingsRepository
import com.lifthelper.domain.repository.WorkoutRepository
import com.lifthelper.domain.usecase.CheckPersonalRecordsUseCase
import com.lifthelper.domain.usecase.GetAnalyticsSummaryUseCase
import com.lifthelper.domain.usecase.LoadCalibrationUseCase
import com.lifthelper.domain.usecase.ProcessWorkoutFrameUseCase
import com.lifthelper.domain.usecase.SaveWorkoutUseCase
import com.lifthelper.domain.vision.IPoseDetector
import com.lifthelper.domain.vision.IPositionCalculator
import com.lifthelper.domain.vision.IVelocityCalculator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped

/**
 * Dagger Hilt module for providing use cases.
 *
 * Use cases are scoped to ViewModels since they typically coordinate
 * operations on behalf of a specific screen or feature.
 */
@Module
@InstallIn(ViewModelComponent::class)
object UseCaseModule {

    /**
     * Provides ProcessWorkoutFrameUseCase.
     */
    @Provides
    @ViewModelScoped
    fun provideProcessWorkoutFrameUseCase(
        poseDetector: IPoseDetector,
        velocityCalculator: IVelocityCalculator,
        positionCalculator: IPositionCalculator,
        strategyFactory: ExerciseStrategyFactory,
        settingsRepository: SettingsRepository,
        logger: Logger
    ): ProcessWorkoutFrameUseCase {
        return ProcessWorkoutFrameUseCase(
            poseDetector = poseDetector,
            velocityCalculator = velocityCalculator,
            positionCalculator = positionCalculator,
            strategyFactory = strategyFactory,
            settingsRepository = settingsRepository,
            logger = logger
        )
    }

    /**
     * Provides SaveWorkoutUseCase.
     */
    @Provides
    @ViewModelScoped
    fun provideSaveWorkoutUseCase(
        workoutRepository: WorkoutRepository,
        logger: Logger
    ): SaveWorkoutUseCase {
        return SaveWorkoutUseCase(workoutRepository, logger)
    }

    /**
     * Provides LoadCalibrationUseCase.
     */
    @Provides
    @ViewModelScoped
    fun provideLoadCalibrationUseCase(
        calibrationRepository: CalibrationRepository
    ): LoadCalibrationUseCase {
        return LoadCalibrationUseCase(calibrationRepository)
    }

    @Provides
    @ViewModelScoped
    fun provideGetAnalyticsSummaryUseCase(
        analyticsRepository: AnalyticsRepository
    ): GetAnalyticsSummaryUseCase {
        return GetAnalyticsSummaryUseCase(analyticsRepository)
    }

    @Provides
    @ViewModelScoped
    fun provideCheckPersonalRecordsUseCase(
        analyticsRepository: AnalyticsRepository
    ): CheckPersonalRecordsUseCase {
        return CheckPersonalRecordsUseCase(analyticsRepository)
    }
}
