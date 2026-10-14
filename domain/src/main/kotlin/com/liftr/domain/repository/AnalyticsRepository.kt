package com.liftr.domain.repository

import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.JumpRepRecord
import com.liftr.domain.model.PersonalRecord
import com.liftr.domain.model.TimeRange
import com.liftr.domain.model.TrendDataPoint
import com.liftr.domain.model.WeeklyVolume
import com.liftr.domain.model.Workout
import com.liftr.domain.value.Kilograms

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

    /** Every jump of [exerciseType] in [timeRange], oldest first. */
    suspend fun getJumpReps(exerciseType: ExerciseType, timeRange: TimeRange): List<JumpRepRecord>

    suspend fun getAvailableWeights(exerciseType: ExerciseType): List<Kilograms>

    suspend fun checkForNewPRs(
        exerciseType: ExerciseType,
        set: com.liftr.domain.model.Set,
        sessionTonnage: Float
    ): List<PersonalRecord>
}
