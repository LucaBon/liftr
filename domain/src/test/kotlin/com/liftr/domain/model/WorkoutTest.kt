package com.liftr.domain.model

import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.RepCount
import com.liftr.domain.value.Watts
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class WorkoutTest {

    @Test
    fun `should create Workout with all fields`() {
        // Given
        val date = Date()
        val duration = 3600000L // 1 hour
        val notes = "Great workout"
        val sets = listOf(createTestSet(), createTestSet())

        // When
        val workout = Workout(
            id = 1,
            date = date,
            duration = duration,
            notes = notes,
            sets = sets
        )

        // Then
        assertEquals(1, workout.id)
        assertEquals(date, workout.date)
        assertEquals(duration, workout.duration)
        assertEquals(notes, workout.notes)
        assertEquals(2, workout.sets.size)
    }

    @Test
    fun `should create Workout with default values`() {
        // Given
        val date = Date()
        val duration = 1000L

        // When
        val workout = Workout(
            date = date,
            duration = duration
        )

        // Then
        assertEquals(0, workout.id)
        assertEquals("", workout.notes)
        assertEquals(0, workout.sets.size)
    }

    @Test
    fun `should calculate total reps correctly with multiple sets`() {
        // Given
        val set1 = createTestSet(completedReps = RepCount(5))
        val set2 = createTestSet(completedReps = RepCount(3))
        val set3 = createTestSet(completedReps = RepCount(4))
        val workout = Workout(
            date = Date(),
            duration = 1000L,
            sets = listOf(set1, set2, set3)
        )

        // When
        val totalReps = workout.getTotalReps()

        // Then
        assertEquals(12, totalReps)
    }

    @Test
    fun `should return zero total reps for empty sets`() {
        // Given
        val workout = Workout(
            date = Date(),
            duration = 1000L,
            sets = emptyList()
        )

        // When
        val totalReps = workout.getTotalReps()

        // Then
        assertEquals(0, totalReps)
    }

    @Test
    fun `should calculate average velocity correctly with multiple sets`() {
        // Given
        val set1 = createTestSet(avgVelocity = MetersPerSecond(0.6f))
        val set2 = createTestSet(avgVelocity = MetersPerSecond(0.8f))
        val set3 = createTestSet(avgVelocity = MetersPerSecond(0.7f))
        val workout = Workout(
            date = Date(),
            duration = 1000L,
            sets = listOf(set1, set2, set3)
        )

        // When
        val avgVelocity = workout.getAverageVelocity()

        // Then
        assertEquals(0.7f, avgVelocity.value, 0.001f)
    }

    @Test
    fun `should return zero average velocity for empty sets`() {
        // Given
        val workout = Workout(
            date = Date(),
            duration = 1000L,
            sets = emptyList()
        )

        // When
        val avgVelocity = workout.getAverageVelocity()

        // Then
        assertEquals(0f, avgVelocity.value, 0.001f)
    }

    @Test
    fun `should return peak velocity correctly with multiple sets`() {
        // Given
        val set1 = createTestSet(peakVelocity = MetersPerSecond(0.9f))
        val set2 = createTestSet(peakVelocity = MetersPerSecond(1.2f))
        val set3 = createTestSet(peakVelocity = MetersPerSecond(0.8f))
        val workout = Workout(
            date = Date(),
            duration = 1000L,
            sets = listOf(set1, set2, set3)
        )

        // When
        val peakVelocity = workout.getPeakVelocity()

        // Then
        assertEquals(1.2f, peakVelocity.value, 0.001f)
    }

    @Test
    fun `should return zero peak velocity for empty sets`() {
        // Given
        val workout = Workout(
            date = Date(),
            duration = 1000L,
            sets = emptyList()
        )

        // When
        val peakVelocity = workout.getPeakVelocity()

        // Then
        assertEquals(0f, peakVelocity.value, 0.001f)
    }

    @Test
    fun `should test data class equality`() {
        // Given
        val date = Date()
        val workout1 = Workout(
            id = 1,
            date = date,
            duration = 1000L,
            notes = "Test"
        )
        val workout2 = Workout(
            id = 1,
            date = date,
            duration = 1000L,
            notes = "Test"
        )

        // Then
        assertEquals(workout1, workout2)
        assertEquals(workout1.hashCode(), workout2.hashCode())
    }

    @Test
    fun `should test data class copy`() {
        // Given
        val date = Date()
        val workout = Workout(
            id = 1,
            date = date,
            duration = 1000L,
            notes = "Original"
        )

        // When
        val copiedWorkout = workout.copy(notes = "Updated")

        // Then
        assertEquals(1, copiedWorkout.id)
        assertEquals(date, copiedWorkout.date)
        assertEquals(1000L, copiedWorkout.duration)
        assertEquals("Updated", copiedWorkout.notes)
    }

    // Helper function to create test sets
    private fun createTestSet(
        completedReps: RepCount = RepCount(5),
        avgVelocity: MetersPerSecond = MetersPerSecond(0.5f),
        peakVelocity: MetersPerSecond = MetersPerSecond(0.8f)
    ): Set {
        return Set(
            id = 0,
            workoutId = 0,
            exerciseType = ExerciseType.BACK_SQUAT,
            weight = Kilograms(100f),
            targetReps = RepCount(5),
            completedReps = completedReps,
            avgVelocity = avgVelocity,
            peakVelocity = peakVelocity,
            timestamp = System.currentTimeMillis()
        )
    }
}
