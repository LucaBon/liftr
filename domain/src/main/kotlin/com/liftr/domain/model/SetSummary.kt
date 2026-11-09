package com.liftr.domain.model

import com.liftr.domain.calculator.CameraView
import com.liftr.domain.calculator.JumpMechanics
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
 * @property repJumpQualities Per-jump [JumpQuality] from the free-fall check, aligned with
 *   [repJumpHeights]; empty (every jump counts) for lifts and summaries built without it
 * @property repMechanics Per-jump mean push-off force, velocity and power (Samozino), null where
 *   body mass or the push-off distance is unknown; empty for lifts
 * @property repContactTimesMs Per-jump ground contact before the takeoff, in ms, for rebound jumps
 *   (see [JumpData.contactTimeMs]); null for the first jump, after a rest and for approach jumps
 * @property repReactiveStrengthIndices Per-jump RSI (flight time / contact time), null where there
 *   is no contact time; both index-aligned with [repJumpHeights]
 * @property repEccentricVelocities Per-rep mean eccentric velocity (range of motion over the
 *   descent time) for squats analyzed from video; empty elsewhere. [repVelocities] and
 *   [repPeakVelocities] stay the mean and peak concentric velocity.
 * @property repPauseMs Per-rep bottom pause in ms (0 without one); squats from video only
 * @property repEccentricDurations Per-rep descent time in seconds; squats from video only
 * @property repConcentricDurations Per-rep ascent time in seconds; squats from video only
 * @property repRangeOfMotion Per-rep bar travel from standing to the bottom; squats from video only
 * @property barTrackSource What the squat velocities were measured on; null where no bar track was built
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
    val repCatchTypes: List<CatchType?> = emptyList(),
    val repJumpQualities: List<JumpQuality> = emptyList(),
    val repMechanics: List<JumpMechanics?> = emptyList(),
    val repContactTimesMs: List<Float?> = emptyList(),
    val repReactiveStrengthIndices: List<Float?> = emptyList(),
    val repEccentricVelocities: List<MetersPerSecond> = emptyList(),
    val repPauseMs: List<Long> = emptyList(),
    val repEccentricDurations: List<Float> = emptyList(),
    val repConcentricDurations: List<Float> = emptyList(),
    val repRangeOfMotion: List<Meters> = emptyList(),
    val barTrackSource: BarTrackSource? = null
) {
    val overallGrade: ScoreGrade get() = ScoreGrade.fromScore(overallScore)

    /** The jump's quality; UNCHECKED where none was recorded, so it keeps counting. */
    fun jumpQuality(index: Int): JumpQuality = repJumpQualities.getOrElse(index) { JumpQuality.UNCHECKED }

    /**
     * Indices of the jumps that count toward best and average: suspect ones
     * are left out, unless every jump is suspect (see [jumpsThatCount]).
     */
    private val countedJumpIndices: List<Int>
        get() = repJumpHeights.indices.toList().jumpsThatCount(::jumpQuality)

    /** How many jumps were left out of best and average for failing the free-fall check. */
    val excludedJumpCount: Int get() = repJumpHeights.indices.toList().excludedJumpCount(::jumpQuality)

    /** Every jump failed the free-fall check: best and average use them anyway, with a warning. */
    val allJumpsSuspect: Boolean get() = repJumpHeights.indices.toList().allJumpsSuspect(::jumpQuality)

    val bestJumpHeight: Meters? get() = countedJumpIndices.map { repJumpHeights[it] }.maxByOrNull { it.value }

    /**
     * The set's height for its personal record: like [bestJumpHeight], but null
     * when every jump is suspect, so a badly tracked set can't become a record.
     */
    val recordJumpHeight: Meters? get() = bestJumpHeight.takeUnless { allJumpsSuspect }

    val bestApexHeight: Meters? get() = countedApexHeights().maxByOrNull { it.value }

    val avgApexHeight: Meters?
        get() = countedApexHeights().takeIf { it.isNotEmpty() }
            ?.let { heights -> Meters(heights.map { it.value }.average().toFloat()) }

    /** Reps per catch type, in enum order, for the types that occurred. */
    val catchCounts: Map<CatchType, Int>
        get() = repCatchTypes.filterNotNull().groupingBy { it }.eachCount()
            .toSortedMap(compareBy { it.ordinal })

    val avgJumpHeight: Meters?
        get() = countedJumpIndices.map { repJumpHeights[it] }.takeIf { it.isNotEmpty() }
            ?.let { heights -> Meters(heights.map { it.value }.average().toFloat()) }

    private fun countedApexHeights(): List<Meters> =
        countedJumpIndices.mapNotNull { repApexHeights.getOrNull(it) }

    /** RSIs of the rebound jumps that count, as for height: suspect ones are left out. */
    private fun countedRsis(): List<Float> =
        repReactiveStrengthIndices.indices.toList().jumpsThatCount(::jumpQuality)
            .mapNotNull { repReactiveStrengthIndices[it] }

    /** Highest RSI of the set's rebound jumps; null without any. */
    val bestRsi: Float? get() = countedRsis().maxOrNull()

    /**
     * Mean of the [RSI_BEST_OF] best RSIs, in the manner of the RSI 10/5 test
     * (ten rebounds, the best five averaged): one lucky bounce weighs less
     * than in [bestRsi]. Null with fewer rebound jumps than that.
     */
    val bestFiveMeanRsi: Float?
        get() = countedRsis()
            .takeIf { it.size >= RSI_BEST_OF }
            ?.sortedDescending()?.take(RSI_BEST_OF)?.average()?.toFloat()

    /** The set's RSI: [bestFiveMeanRsi] with enough rebound jumps, else [bestRsi]; null without any. */
    val setRsi: Float? get() = bestFiveMeanRsi ?: bestRsi

    companion object {
        /** Rebound jumps whose RSI the set's [bestFiveMeanRsi] averages. */
        const val RSI_BEST_OF = 5
    }
}

/** Where a squat's bar kinematics came from. */
enum class BarTrackSource {
    /** The plate's center, scaled by its own diameter: the bar plane. */
    PLATE,

    /** The tracked landmark, scaled by the body: an estimate, the plate wasn't found. */
    LANDMARKS
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
