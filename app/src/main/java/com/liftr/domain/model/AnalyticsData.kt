package com.liftr.domain.model

import com.liftr.domain.value.Kilograms

enum class TimeRange(val displayName: String, val days: Int) {
    ONE_WEEK("1W", 7),
    ONE_MONTH("1M", 30),
    THREE_MONTHS("3M", 90),
    SIX_MONTHS("6M", 180),
    ONE_YEAR("1Y", 365),
    ALL("All", Int.MAX_VALUE)
}

data class TrendDataPoint(
    val timestamp: Long,
    val value: Float,
    val label: String = ""
)

data class WeeklyVolume(
    val weekStartTimestamp: Long,
    val exerciseType: ExerciseType?,
    val tonnage: Kilograms
)

data class AnalyticsSummary(
    val personalRecords: List<PersonalRecord>,
    val velocityTrend: List<TrendDataPoint>,
    val weeklyVolumes: List<WeeklyVolume>,
    val estimated1RMTrend: List<TrendDataPoint>,
    val qualityScoreTrend: List<TrendDataPoint>,
    val recentWorkouts: List<Workout>,
    val totalWorkouts: Int,
    val totalSets: Int,
    val totalReps: Int
)
