package com.lifthelper.domain.exercise

import com.lifthelper.domain.model.DepthValidationResult
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.RepQualityScore
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Stateless scorer for individual rep quality.
 *
 * Scores each rep on a 0-100 scale across 4 weighted components:
 * - Depth (30 pts): Did the rep reach required depth?
 * - Bar Path (20 pts): How straight/consistent was the bar path?
 * - Tempo (20 pts): Was the eccentric controlled? Was the concentric consistent?
 * - Form (30 pts): Were there form violations?
 *
 * Pure functions, no Android dependencies. Follows the same stateless object
 * pattern as [VelocityLossTracker].
 */
object RepQualityScorer {

    /**
     * Scores a single rep based on depth, bar path, tempo, and form.
     *
     * @param exerciseType The exercise being performed
     * @param depthValidation Depth validation result from the strategy (null if no depth data)
     * @param barXPositions Horizontal bar positions collected each frame during the rep
     * @param eccentricDuration Duration of the eccentric (lowering) phase in seconds
     * @param concentricDuration Duration of the concentric (lifting) phase in seconds
     * @param previousConcentricDurations Concentric durations of previous reps in this set
     * @param formIssues Form issues detected during this rep
     * @return Quality score with per-component breakdown
     */
    fun scoreRep(
        exerciseType: ExerciseType,
        depthValidation: DepthValidationResult?,
        barXPositions: List<Float>,
        eccentricDuration: Float,
        concentricDuration: Float,
        previousConcentricDurations: List<Float>,
        formIssues: List<FormIssue>
    ): RepQualityScore {
        val depth = scoreDepth(depthValidation)
        val barPath = scoreBarPath(barXPositions)
        val tempo = scoreTempo(eccentricDuration, concentricDuration, previousConcentricDurations)
        val form = scoreForm(formIssues)

        return RepQualityScore(
            totalScore = depth + barPath + tempo + form,
            depthScore = depth,
            barPathScore = barPath,
            tempoScore = tempo,
            formScore = form,
            depthAchieved = depthValidation?.isValid ?: false,
            formIssues = formIssues
        )
    }

    /**
     * Depth scoring (0-30 points).
     *
     * Uses the multi-method DepthValidationResult confidence score for graduated scoring:
     * - Valid with high confidence (>0.8): 30 pts
     * - Valid with medium confidence (>0.6): 25 pts
     * - Valid with low confidence: 20 pts
     * - Invalid: partial credit based on confidence (up to 15 pts)
     * - No data: 15 pts (benefit of the doubt)
     */
    internal fun scoreDepth(result: DepthValidationResult?): Int {
        if (result == null) return 15
        return when {
            result.isValid && result.confidence > 0.8f -> 30
            result.isValid && result.confidence > 0.6f -> 25
            result.isValid -> 20
            else -> (result.confidence * 15).toInt()
        }
    }

    /**
     * Bar path scoring (0-20 points).
     *
     * Measures horizontal deviation (standard deviation of X positions).
     * Lower deviation = straighter bar path = higher score.
     * Maximum acceptable deviation is 10cm (0.10m).
     *
     * Returns 15 pts (partial credit) if insufficient data (<3 samples).
     */
    internal fun scoreBarPath(positions: List<Float>): Int {
        if (positions.size < 3) return 15
        val mean = positions.average().toFloat()
        val variance = positions.map { (it - mean).pow(2) }.average().toFloat()
        val stdDev = sqrt(variance)
        val maxDev = 0.10f
        return ((1f - (stdDev / maxDev)).coerceIn(0f, 1f) * 20).toInt()
    }

    /**
     * Tempo scoring (0-20 points).
     *
     * Two sub-components of 10 points each:
     * 1. Eccentric control (10 pts): Penalizes dropping the weight (<0.5s)
     * 2. Concentric consistency (10 pts): How consistent is the lifting speed vs previous reps?
     *    First rep always gets full consistency marks.
     */
    internal fun scoreTempo(
        eccentricDuration: Float,
        concentricDuration: Float,
        previousConcentricDurations: List<Float>
    ): Int {
        val eccentricScore = when {
            eccentricDuration >= 1.0f -> 10
            eccentricDuration >= 0.5f -> 5
            else -> 0
        }

        val consistencyScore = if (previousConcentricDurations.isNotEmpty()) {
            val avg = previousConcentricDurations.average().toFloat()
            if (avg > 0f) {
                val deviation = abs(concentricDuration - avg) / avg
                ((1f - deviation).coerceIn(0f, 1f) * 10).toInt()
            } else 10
        } else 10 // First rep gets full marks

        return eccentricScore + consistencyScore
    }

    /**
     * Form scoring (0-30 points).
     *
     * Starts at 30 and subtracts for each form issue based on severity:
     * - CRITICAL: -15 points (injury risk)
     * - WARNING: -8 points (should be corrected)
     * - INFO: -3 points (informational)
     *
     * Score is floored at 0.
     */
    internal fun scoreForm(issues: List<FormIssue>): Int {
        var score = 30
        issues.forEach { issue ->
            score -= when (issue.severity) {
                Severity.CRITICAL -> 15
                Severity.WARNING -> 8
                Severity.INFO -> 3
            }
        }
        return score.coerceAtLeast(0)
    }
}
