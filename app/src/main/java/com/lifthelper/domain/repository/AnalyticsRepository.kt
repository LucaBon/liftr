package com.lifthelper.domain.repository

import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PersonalRecord
import com.lifthelper.domain.model.TimeRange
import com.lifthelper.domain.model.TrendDataPoint
import com.lifthelper.domain.model.WeeklyVolume
import com.lifthelper.domain.model.Workout
import com.lifthelper.domain.value.Kilograms

interface AnalyticsRepository {

    suspend fun getPersonalRecords(exerciseType: ExerciseType?): List<PersonalRecord>

    suspend fun getVelocityTrend(
        exerciseType: ExerciseType,
        timeRange: TimeRange,
        weightFilter: Kilograms? = null
    ): List<TrendDataPoint>

    suspend fun getWeeklyVolumes(
        exerciseType: ExerciseType?,
        timeRange: TimeRange
    ): List<WeeklyVolume>

    suspend fun getEstimated1RMTrend(
        exerciseType: ExerciseType,
        timeRange: TimeRange
    ): List<TrendDataPoint>

    suspend fun getQualityScoreTrend(
        exerciseType: ExerciseType,
        timeRange: TimeRange
    ): List<TrendDataPoint>

    suspend fun getRecentWorkouts(limit: Int = 10): List<Workout>

    suspend fun getAvailableWeights(exerciseType: ExerciseType): List<Kilograms>

    suspend fun checkForNewPRs(
        exerciseType: ExerciseType,
        set: com.lifthelper.domain.model.Set,
        sessionTonnage: Float
    ): List<PersonalRecord>
}
