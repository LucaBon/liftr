package com.lifthelper.domain.model

import com.lifthelper.domain.exercise.FormIssue
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.value.RepCount
import com.lifthelper.domain.model.BarPathData

/**
 * Summary of a completed set, including per-rep quality scores and aggregate metrics.
 *
 * Generated when the user stops recording and displayed as a post-set summary card.
 *
 * @property exerciseType The exercise performed
 * @property weight Weight lifted
 * @property targetReps Target rep count for the set
 * @property completedReps Actual reps completed
 * @property repScores Per-rep quality scores
 * @property repVelocities Per-rep average velocities (m/s)
 * @property avgVelocity Average velocity across all reps
 * @property peakVelocity Highest single-rep velocity
 * @property velocityLossPercent Velocity loss from fastest to slowest rep (0.0-1.0)
 * @property overallScore Average of all rep scores
 * @property bestRepIndex Index of the highest-scoring rep
 * @property worstRepIndex Index of the lowest-scoring rep
 * @property aggregateFormIssues Form issues grouped with which reps they occurred in
 */
data class SetSummary(
    val exerciseType: ExerciseType,
    val weight: Kilograms,
    val targetReps: RepCount,
    val completedReps: RepCount,
    val repScores: List<RepQualityScore>,
    val repVelocities: List<MetersPerSecond>,
    val avgVelocity: MetersPerSecond,
    val peakVelocity: MetersPerSecond,
    val velocityLossPercent: Float,
    val overallScore: Int,
    val bestRepIndex: Int,
    val worstRepIndex: Int,
    val aggregateFormIssues: List<AggregateFormIssue>,
    val repBarPaths: List<BarPathData> = emptyList()
) {
    val overallGrade: ScoreGrade get() = ScoreGrade.fromScore(overallScore)
}

/**
 * A form issue aggregated across multiple reps.
 *
 * @property issue The form issue
 * @property repNumbers 1-indexed rep numbers where this issue occurred
 */
data class AggregateFormIssue(
    val issue: FormIssue,
    val repNumbers: List<Int>
)
