package com.liftr.domain.model

import com.liftr.domain.calculator.CameraView
import com.liftr.domain.exercise.FormIssue
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.RepCount
import com.liftr.domain.value.Watts
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
 * @property peakVelocity Highest instantaneous velocity of any rep
 * @property repPeakVelocities Per-rep peak velocities (m/s), empty when not measured
 * @property velocityLossPercent Velocity loss from fastest to slowest rep (0.0-1.0)
 * @property overallScore Average of all rep scores
 * @property bestRepIndex Index of the highest-scoring rep
 * @property worstRepIndex Index of the lowest-scoring rep
 * @property aggregateFormIssues Form issues grouped with which reps they occurred in
 * @property repApexHeights Per-jump height from takeoff to the top of the flight
 *   (null where it couldn't be measured), alongside [repJumpHeights] from flight time
 * @property repPeakPowers Per-jump peak power (Sayers), null where body mass is unknown; empty for lifts
 * @property repCatchTypes Per-rep catch (snatch and clean), null where not judged; empty for other lifts
 * @property cameraView How the set was filmed (every exercise should be filmed side-on); UNKNOWN when it can't be judged (bench press, too few standing frames)
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
    val repPeakVelocities: List<MetersPerSecond> = emptyList(),
    val cameraView: CameraView = CameraView.UNKNOWN,
    /** Jump flights imply stretched time (slow motion read as real time): heights are untrustworthy. */
    val videoSpeedSuspect: Boolean = false,
    /** A jump flight took the head out of the top of the frame: the camera is too close or too low. */
    val headOutOfFrame: Boolean = false,
    val repPeakPowers: List<Watts?> = emptyList(),
    val repCatchTypes: List<CatchType?> = emptyList()
) {
    val overallGrade: ScoreGrade get() = ScoreGrade.fromScore(overallScore)

    val bestJumpHeight: Meters? get() = repJumpHeights.maxByOrNull { it.value }

    val bestApexHeight: Meters? get() = repApexHeights.filterNotNull().maxByOrNull { it.value }

    val avgApexHeight: Meters?
        get() = repApexHeights.filterNotNull().takeIf { it.isNotEmpty() }
            ?.let { heights -> Meters(heights.map { it.value }.average().toFloat()) }

    /** Reps per catch type, in enum order, for the types that occurred. */
    val catchCounts: Map<CatchType, Int>
        get() = repCatchTypes.filterNotNull().groupingBy { it }.eachCount()
            .toSortedMap(compareBy { it.ordinal })

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

/**
 * Groups the reps' form issues by [FormIssue.kind], listing the reps each
 * kind appeared in and showing its most severe instance in the set.
 */
fun aggregateFormIssues(repScores: List<RepQualityScore>): List<AggregateFormIssue> {
    val byKind = LinkedHashMap<String, Pair<FormIssue, MutableList<Int>>>()
    repScores.forEachIndexed { index, score ->
        score.formIssues.forEach { issue ->
            val entry = byKind[issue.kind]
            when {
                entry == null -> byKind[issue.kind] = issue to mutableListOf(index + 1)
                else -> {
                    if (index + 1 !in entry.second) entry.second.add(index + 1)
                    if (issue.severity > entry.first.severity) byKind[issue.kind] = issue to entry.second
                }
            }
        }
    }
    return byKind.values.map { (issue, repNumbers) -> AggregateFormIssue(issue, repNumbers) }
}
