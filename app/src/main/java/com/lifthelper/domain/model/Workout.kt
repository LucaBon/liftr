package com.lifthelper.domain.model

import com.lifthelper.domain.value.MetersPerSecond
import java.util.Date

/**
 * Domain model representing a workout session.
 *
 * This is a pure Kotlin data class with no Android dependencies,
 * suitable for use in the domain and use case layers.
 *
 * @property id Unique identifier for the workout (0 if not yet saved)
 * @property date The date/time when the workout was performed
 * @property duration Duration of the workout in milliseconds
 * @property notes Optional notes about the workout
 * @property sets List of sets performed during this workout
 * @property createdAt Timestamp when this workout was created
 */
data class Workout(
    val id: Long = 0,
    val date: Date,
    val duration: Long,
    val notes: String = "",
    val sets: List<Set> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Returns the total number of reps across all sets.
     */
    fun getTotalReps(): Int = sets.sumOf { it.completedReps.value }

    /**
     * Returns the average velocity across all sets.
     */
    fun getAverageVelocity(): MetersPerSecond {
        if (sets.isEmpty()) return MetersPerSecond(0f)
        return MetersPerSecond(sets.map { it.avgVelocity.value }.average().toFloat())
    }

    /**
     * Returns the peak velocity across all sets.
     */
    fun getPeakVelocity(): MetersPerSecond {
        if (sets.isEmpty()) return MetersPerSecond(0f)
        return sets.map { it.peakVelocity }.maxByOrNull { it.value } ?: MetersPerSecond(0f)
    }
}
