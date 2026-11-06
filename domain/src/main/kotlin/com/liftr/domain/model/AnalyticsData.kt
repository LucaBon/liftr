package com.liftr.domain.model

import com.liftr.domain.calculator.ForceVelocityProfileResult
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
    val totalReps: Int,
    val jumpReps: List<JumpRepRecord> = emptyList(),
    /** Average points per scoring component over the range's reps that stored them. */
    val scoreBreakdown: ScoreBreakdown? = null,
    /** Jump force-velocity profile over the range; only for loaded jumps, null otherwise. */
    val forceVelocityProfile: ForceVelocityProfileResult? = null
)

/**
 * One measured jump, in time order, for the jump trends and the latest set's paths.
 *
 * @property setId The set it belongs to
 * @property repNumber 1-indexed jump number within the set
 * @property loadKg External load of the set (0 for bodyweight), for the force-velocity profile
 */
data class JumpRepRecord(
    val timestamp: Long,
    val setId: Long,
    val repNumber: Int,
    val jump: RepJumpData,
    val loadKg: Float = 0f
)
