package com.liftr.domain.usecase

import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.Set
import com.liftr.domain.repository.CustomExerciseRepository
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.RepCount
import javax.inject.Inject

class LogManualSetUseCase @Inject constructor(
    private val customExerciseRepository: CustomExerciseRepository
) {
    suspend operator fun invoke(
        exerciseName: String,
        weight: Float,
        reps: Int,
        notes: String = ""
    ): Set {
        // Save exercise name for future autocomplete
        customExerciseRepository.addExerciseName(exerciseName)

        return Set(
            workoutId = 0, // assigned during persistence
            exerciseType = ExerciseType.BACK_SQUAT, // placeholder for manual sets
            weight = Kilograms(weight),
            targetReps = RepCount(reps),
            completedReps = RepCount(reps),
            avgVelocity = MetersPerSecond(0f),
            peakVelocity = MetersPerSecond(0f),
            timestamp = System.currentTimeMillis(),
            notes = notes,
            isManual = true,
            exerciseName = exerciseName
        )
    }
}
