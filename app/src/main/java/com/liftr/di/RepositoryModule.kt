package com.liftr.di

import com.liftr.data.repository.AnalyticsRepositoryImpl
import com.liftr.data.repository.CalibrationRepositoryImpl
import com.liftr.data.repository.CustomExerciseRepositoryImpl
import com.liftr.data.repository.SettingsRepositoryImpl
import com.liftr.data.repository.WorkoutRepositoryImpl
import com.liftr.domain.repository.AnalyticsRepository
import com.liftr.domain.repository.CalibrationRepository
import com.liftr.domain.repository.CustomExerciseRepository
import com.liftr.domain.repository.SettingsRepository
import com.liftr.domain.repository.WorkoutRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dagger Hilt module for providing repository implementations.
 *
 * This module binds repository interfaces to their concrete implementations,
 * allowing ViewModels and use cases to depend on abstractions rather than
 * concrete classes, following the Dependency Inversion Principle.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /**
     * Binds WorkoutRepositoryImpl to WorkoutRepository interface.
     */
    @Binds
    @Singleton
    abstract fun bindWorkoutRepository(
        impl: WorkoutRepositoryImpl
    ): WorkoutRepository

    /**
     * Binds CalibrationRepositoryImpl to CalibrationRepository interface.
     */
    @Binds
    @Singleton
    abstract fun bindCalibrationRepository(
        impl: CalibrationRepositoryImpl
    ): CalibrationRepository

    /**
     * Binds SettingsRepositoryImpl to SettingsRepository interface.
     */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: SettingsRepositoryImpl
    ): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindAnalyticsRepository(
        impl: AnalyticsRepositoryImpl
    ): AnalyticsRepository

    @Binds
    @Singleton
    abstract fun bindCustomExerciseRepository(
        impl: CustomExerciseRepositoryImpl
    ): CustomExerciseRepository
}
