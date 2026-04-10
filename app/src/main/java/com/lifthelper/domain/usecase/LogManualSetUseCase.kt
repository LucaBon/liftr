package com.lifthelper.domain.usecase

import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.Set
import com.lifthelper.domain.repository.CustomExerciseRepository
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.value.RepCount
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
