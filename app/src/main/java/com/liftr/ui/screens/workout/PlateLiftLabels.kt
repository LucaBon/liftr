package com.liftr.ui.screens.workout

import androidx.annotation.StringRes
import com.liftr.R
import com.liftr.domain.model.ExerciseType

/** The rest at the turnaround of a plate-tracked lift, named for the lift. */
@StringRes
fun ExerciseType.pauseLabelRes(): Int = when (this) {
    ExerciseType.BENCH_PRESS -> R.string.pause_label_chest
    ExerciseType.DEADLIFT -> R.string.pause_label_floor
    else -> R.string.pause_label_bottom
}

/** Deadlift concentric is the pull and eccentric the lowering; other lifts keep Con/Ecc. */
@StringRes
fun ExerciseType.concentricMeanHeaderRes(): Int =
    if (this == ExerciseType.DEADLIFT) R.string.set_summary_pull_mean_header else R.string.set_summary_con_mean_header

@StringRes
fun ExerciseType.concentricPeakHeaderRes(): Int =
    if (this == ExerciseType.DEADLIFT) R.string.set_summary_pull_peak_header else R.string.set_summary_con_peak_header

@StringRes
fun ExerciseType.eccentricHeaderRes(): Int =
    if (this == ExerciseType.DEADLIFT) R.string.set_summary_lowering_header else R.string.set_summary_ecc_mean_header

@StringRes
fun ExerciseType.eccentricHistoryLabelRes(): Int =
    if (this == ExerciseType.DEADLIFT) R.string.history_lowering_label else R.string.history_ecc_label
