package com.liftr.domain.model

import com.liftr.domain.calculator.CameraView
import com.liftr.domain.exercise.FormIssue
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.RepCount
import com.liftr.domain.model.BarPathData

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
 * @property repApexHeights Per-jump height from takeoff to the top of the flight
 *   (null where it couldn't be measured), alongside [repJumpHeights] from flight time
 * @property cameraView How a jump set was filmed (jumps should be filmed side-on); UNKNOWN for other exercises
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
    val repBarPaths: List<BarPathData> = emptyList(),
    val repJumpHeights: List<Meters> = emptyList(),
    val repApexHeights: List<Meters?> = emptyList(),
    val cameraView: CameraView = CameraView.UNKNOWN
) {
    val overallGrade: ScoreGrade get() = ScoreGrade.fromScore(overallScore)

    val bestJumpHeight: Meters? get() = repJumpHeights.maxByOrNull { it.value }

    val bestApexHeight: Meters? get() = repApexHeights.filterNotNull().maxByOrNull { it.value }

    val avgApexHeight: Meters?
        get() = repApexHeights.filterNotNull().takeIf { it.isNotEmpty() }
            ?.let { heights -> Meters(heights.map { it.value }.average().toFloat()) }

    val avgJumpHeight: Meters?
        get() = if (repJumpHeights.isEmpty()) null
        else Meters(repJumpHeights.map { it.value }.average().toFloat())
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
