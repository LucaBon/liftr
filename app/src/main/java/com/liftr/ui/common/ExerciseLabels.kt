package com.liftr.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.liftr.R
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.Set

/** Localized name of the exercise, for display. */
@get:StringRes
val ExerciseType.labelRes: Int
    get() = when (this) {
        ExerciseType.FRONT_SQUAT -> R.string.exercise_front_squat
        ExerciseType.BACK_SQUAT -> R.string.exercise_back_squat
        ExerciseType.BENCH_PRESS -> R.string.exercise_bench_press
        ExerciseType.DEADLIFT -> R.string.exercise_deadlift
        ExerciseType.CLEAN -> R.string.exercise_clean
        ExerciseType.SNATCH -> R.string.exercise_snatch
        ExerciseType.VERTICAL_JUMP -> R.string.exercise_vertical_jump
        ExerciseType.LOADED_JUMP -> R.string.exercise_loaded_jump
    }

@Composable
fun ExerciseType.label(): String = stringResource(labelRes)

/** The set's custom name for manual entries, otherwise the localized exercise name. */
@Composable
fun Set.label(): String = exerciseName ?: exerciseType.label()

/** One-line description of a tracked set for workout lists: load, reps and velocity, or best height for jumps. */
@Composable
fun Set.trackedSummaryLine(setNumber: Int): String {
    val prefix = "Set $setNumber: ${label()} - "
    val height = jumpHeight
    return if (exerciseType.isJumpExercise() && height != null) {
        val load = if (exerciseType == ExerciseType.LOADED_JUMP) " @ ${weight.value}kg" else ""
        prefix + "${completedReps.value} jumps$load \u00B7 best %.0f cm".format(height.value * 100)
    } else {
        prefix + "${weight.value}kg x ${completedReps.value} reps @ " + "%.2f m/s avg".format(avgVelocity.value)
    }
}
