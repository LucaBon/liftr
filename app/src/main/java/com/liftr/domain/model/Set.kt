package com.liftr.domain.model

import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.Percentage
import com.liftr.domain.value.RepCount
import com.liftr.domain.value.Watts

/**
 * Domain model representing a set of repetitions for an exercise.
 *
 * This is a pure Kotlin data class with no Android dependencies.
 *
 * @property id Unique identifier for the set (0 if not yet saved)
 * @property workoutId The ID of the parent workout
 * @property exerciseType The type of exercise performed
 * @property weight Weight used for this set
 * @property targetReps Target number of repetitions
 * @property completedReps Actual number of repetitions completed
 * @property avgVelocity Average velocity across all reps
 * @property peakVelocity Peak velocity achieved
 * @property avgPower Average power output (optional)
 * @property jumpHeight Jump height (for jump exercises, optional)
 * @property timestamp Timestamp when the set was completed
 * @property notes Optional notes about the set
 * @property reps List of individual rep data
 */
data class Set(
    val id: Long = 0,
    val workoutId: Long,
    val exerciseType: ExerciseType,
    val weight: Kilograms,
    val targetReps: RepCount,
    val completedReps: RepCount,
    val avgVelocity: MetersPerSecond,
    val peakVelocity: MetersPerSecond,
    val avgPower: Watts? = null,
    val jumpHeight: Meters? = null,
    val timestamp: Long,
    val notes: String = "",
    val reps: List<Rep> = emptyList(),
    val isManual: Boolean = false,
    val exerciseName: String? = null
) {
    val displayName: String
        get() = exerciseName ?: exerciseType.getDisplayName()

    /**
     * Returns whether the target reps were achieved.
     */
    fun isTargetAchieved(): Boolean = completedReps >= targetReps

    /**
     * Returns the percentage of target reps completed.
     */
    fun getCompletionPercentage(): Percentage {
        if (targetReps.value == 0) return Percentage(0f)
        return Percentage((completedReps.value.toFloat() / targetReps.value.toFloat()) * 100f)
    }
}
