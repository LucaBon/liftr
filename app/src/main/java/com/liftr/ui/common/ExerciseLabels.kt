package com.liftr.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.liftr.R
import com.liftr.domain.model.CatchType
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
        ExerciseType.APPROACH_JUMP -> R.string.exercise_approach_jump
    }

@Composable
fun ExerciseType.label(): String = stringResource(labelRes)

/** Name of the score's depth part: a deadlift has no depth, so it scores its lockout there. */
@get:StringRes
val ExerciseType.depthPartLabelRes: Int
    get() = if (this == ExerciseType.DEADLIFT) R.string.score_part_lockout else R.string.set_summary_depth_header

/** Localized name of how the bar was caught. */
@Composable
fun CatchType.label(): String = stringResource(
    when (this) {
        CatchType.MUSCLE -> R.string.catch_muscle
        CatchType.POWER -> R.string.catch_power
        CatchType.SQUAT -> R.string.catch_squat
    }
)

/** "3 power, 1 muscle": reps per catch type. */
@Composable
fun Map<CatchType, Int>.catchCountsText(): String =
    entries.map { (type, count) -> "$count ${type.label().lowercase()}" }.joinToString(", ")

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
        // Snatch and clean reps also say how the bar was caught.
        val catches = reps.mapNotNull { it.catchType }.groupingBy { it }.eachCount().toSortedMap(compareBy { it.ordinal })
        prefix + "${weight.value}kg x ${completedReps.value} reps @ " + "%.2f m/s avg".format(avgVelocity.value) +
            if (catches.isEmpty()) "" else " \u00B7 ${catches.catchCountsText()}"
    }
}
