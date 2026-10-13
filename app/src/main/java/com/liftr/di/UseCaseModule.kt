package com.liftr.di

import com.liftr.domain.exercise.ExerciseStrategyFactory
import com.liftr.domain.logging.Logger
import com.liftr.domain.repository.AnalyticsRepository
import com.liftr.domain.repository.CalibrationRepository
import com.liftr.domain.repository.SettingsRepository
import com.liftr.domain.repository.WorkoutRepository
import com.liftr.domain.usecase.CheckPersonalRecordsUseCase
import com.liftr.domain.usecase.GenerateExportUseCase
import com.liftr.domain.usecase.GetAnalyticsSummaryUseCase
import com.liftr.domain.usecase.LoadCalibrationUseCase
import com.liftr.domain.usecase.ProcessWorkoutFrameUseCase
import com.liftr.domain.usecase.SaveWorkoutUseCase
import com.liftr.domain.vision.IPoseDetector
import com.liftr.domain.vision.IPositionCalculator
import com.liftr.domain.vision.IVelocityCalculator
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

    @Provides
    @ViewModelScoped
    fun provideGenerateExportUseCase(
        workoutRepository: WorkoutRepository
    ): GenerateExportUseCase {
        return GenerateExportUseCase(workoutRepository)
    }
}
