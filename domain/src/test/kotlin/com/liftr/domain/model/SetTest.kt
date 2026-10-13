package com.liftr.domain.model

import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.RepCount
import com.liftr.domain.value.Watts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetTest {

    @Test
    fun `should create Set with all required fields`() {
        // Given
        val exerciseType = ExerciseType.BACK_SQUAT
        val weight = Kilograms(100f)
        val targetReps = RepCount(5)
        val completedReps = RepCount(5)
        val avgVelocity = MetersPerSecond(0.6f)
        val peakVelocity = MetersPerSecond(1.0f)
        val timestamp = System.currentTimeMillis()

        // When
        val set = Set(
            id = 1,
            workoutId = 1,
            exerciseType = exerciseType,
            weight = weight,
            targetReps = targetReps,
            completedReps = completedReps,
            avgVelocity = avgVelocity,
            peakVelocity = peakVelocity,
            timestamp = timestamp
        )

        // Then
        assertEquals(1, set.id)
        assertEquals(1, set.workoutId)
        assertEquals(exerciseType, set.exerciseType)
        assertEquals(weight, set.weight)
        assertEquals(targetReps, set.targetReps)
        assertEquals(completedReps, set.completedReps)
        assertEquals(avgVelocity, set.avgVelocity)
        assertEquals(peakVelocity, set.peakVelocity)
        assertEquals(timestamp, set.timestamp)
    }

    @Test
    fun `should create Set with optional fields`() {
        // Given
        val avgPower = Watts(500f)
        val jumpHeight = Meters(0.5f)
        val notes = "Good set"

        // When
        val set = createTestSet(
            avgPower = avgPower,
            jumpHeight = jumpHeight,
            notes = notes
        )

        // Then
        assertEquals(avgPower, set.avgPower)
        assertEquals(jumpHeight, set.jumpHeight)
        assertEquals(notes, set.notes)
    }

    @Test
    fun `should return true when target reps are achieved`() {
        // Given
        val set = createTestSet(
            targetReps = RepCount(5),
            completedReps = RepCount(5)
        )

        // When
        val isAchieved = set.isTargetAchieved()

        // Then
        assertTrue(isAchieved)
    }

    @Test
    fun `should return true when more than target reps are completed`() {
        // Given
        val set = createTestSet(
            targetReps = RepCount(5),
            completedReps = RepCount(6)
        )

        // When
        val isAchieved = set.isTargetAchieved()

        // Then
        assertTrue(isAchieved)
    }

    @Test
    fun `should return false when target reps are not achieved`() {
        // Given
        val set = createTestSet(
            targetReps = RepCount(5),
            completedReps = RepCount(3)
        )

        // When
        val isAchieved = set.isTargetAchieved()

        // Then
        assertFalse(isAchieved)
    }

    @Test
    fun `should calculate 100% completion when all reps completed`() {
        // Given
        val set = createTestSet(
            targetReps = RepCount(5),
            completedReps = RepCount(5)
        )

        // When
        val percentage = set.getCompletionPercentage()

        // Then
        assertEquals(100f, percentage.value, 0.001f)
    }

    @Test
    fun `should calculate 60% completion when 3 out of 5 reps completed`() {
        // Given
        val set = createTestSet(
            targetReps = RepCount(5),
            completedReps = RepCount(3)
        )

        // When
        val percentage = set.getCompletionPercentage()

        // Then
        assertEquals(60f, percentage.value, 0.001f)
    }

    @Test
    fun `should return 0% completion when target is zero`() {
        // Given
        val set = createTestSet(
            targetReps = RepCount(0),
            completedReps = RepCount(0)
        )

        // When
        val percentage = set.getCompletionPercentage()

        // Then
        assertEquals(0f, percentage.value, 0.001f)
    }

    @Test
    fun `should test data class equality`() {
        // Given
        val timestamp = System.currentTimeMillis()
        val set1 = createTestSet(timestamp = timestamp)
        val set2 = createTestSet(timestamp = timestamp)

        // Then
        assertEquals(set1, set2)
        assertEquals(set1.hashCode(), set2.hashCode())
    }

    @Test
    fun `should test data class copy`() {
        // Given
        val set = createTestSet(completedReps = RepCount(3))

        // When
        val copiedSet = set.copy(completedReps = RepCount(5))

        // Then
        assertEquals(RepCount(5), copiedSet.completedReps)
        assertEquals(set.weight, copiedSet.weight)
        assertEquals(set.exerciseType, copiedSet.exerciseType)
    }

    // Helper function to create test sets
    private fun createTestSet(
        id: Long = 0,
        workoutId: Long = 1,
        exerciseType: ExerciseType = ExerciseType.BACK_SQUAT,
        weight: Kilograms = Kilograms(100f),
        targetReps: RepCount = RepCount(5),
        completedReps: RepCount = RepCount(5),
        avgVelocity: MetersPerSecond = MetersPerSecond(0.6f),
        peakVelocity: MetersPerSecond = MetersPerSecond(1.0f),
        avgPower: Watts? = null,
        jumpHeight: Meters? = null,
        timestamp: Long = System.currentTimeMillis(),
        notes: String = "",
        reps: List<Rep> = emptyList()
    ): Set {
        return Set(
            id = id,
            workoutId = workoutId,
            exerciseType = exerciseType,
            weight = weight,
            targetReps = targetReps,
            completedReps = completedReps,
            avgVelocity = avgVelocity,
            peakVelocity = peakVelocity,
            avgPower = avgPower,
            jumpHeight = jumpHeight,
            timestamp = timestamp,
            notes = notes,
            reps = reps
        )
    }
}
