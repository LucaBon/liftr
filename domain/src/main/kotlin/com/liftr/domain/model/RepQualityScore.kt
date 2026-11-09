package com.liftr.domain.model

import com.liftr.domain.exercise.FormIssue

/**
 * Quality score for a single repetition, broken down into 4 components.
 *
 * Total score is on a 0-100 scale:
 * - Depth: 0-30 points (did the rep reach required depth?)
 * - Bar Path: 0-20 points (how straight/consistent was the bar path?)
 * - Tempo: 0-20 points (was eccentric/concentric controlled and symmetric?)
 * - Form: 0-30 points (were there form violations?)
 *
 * Without a bar path ([barPathApplicable] false, the air squat) the other three
 * components are rescaled to 100 and [barPathScore] is 0.
 *
 * @property totalScore Combined score (0-100)
 * @property depthScore Depth component (0-30)
 * @property barPathScore Bar path straightness component (0-20); 0 and unused when not applicable
 * @property tempoScore Tempo control component (0-20)
 * @property formScore Form quality component (0-30)
 * @property depthAchieved Whether the required depth was reached
 * @property formIssues Form issues detected during this rep
 * @property barPathApplicable False when the exercise has no bar path to score
 */
data class RepQualityScore(
    val totalScore: Int,
    val depthScore: Int,
    val barPathScore: Int,
    val tempoScore: Int,
    val formScore: Int,
    val depthAchieved: Boolean,
    val formIssues: List<FormIssue>,
    val barPathApplicable: Boolean = true
) {
    val grade: ScoreGrade get() = ScoreGrade.fromScore(totalScore)

    val parts: RepScoreParts get() = RepScoreParts(depthScore, barPathScore, tempoScore, formScore)
}

/** A rep's points in each scoring component (depth /30, bar path /20, tempo /20, form /30). */
data class RepScoreParts(
    val depth: Int,
    val barPath: Int,
    val tempo: Int,
    val form: Int
)

/** Average points per scoring component over [repCount] reps. */
data class ScoreBreakdown(
    val depth: Float,
    val barPath: Float,
    val tempo: Float,
    val form: Float,
    val repCount: Int
) {
    companion object {
        /** Null when there is nothing to average. */
        fun of(parts: List<RepScoreParts>): ScoreBreakdown? {
            if (parts.isEmpty()) return null
            return ScoreBreakdown(
                depth = parts.map { it.depth }.average().toFloat(),
                barPath = parts.map { it.barPath }.average().toFloat(),
                tempo = parts.map { it.tempo }.average().toFloat(),
                form = parts.map { it.form }.average().toFloat(),
                repCount = parts.size
            )
        }
    }
}

/**
 * Letter-grade classification for rep quality scores.
 *
 * @property label Human-readable label
 * @property colorHex Color for UI display (ARGB format)
 */
enum class ScoreGrade(val label: String, val colorHex: Long) {
    EXCELLENT("Excellent", 0xFF4CAF50),
    GOOD("Good", 0xFF8BC34A),
    FAIR("Fair", 0xFFFFEB3B),
    POOR("Poor", 0xFFFF9800),
    BAD("Bad", 0xFFE53935);

    companion object {
        fun fromScore(score: Int): ScoreGrade = when {
            score >= 90 -> EXCELLENT
            score >= 75 -> GOOD
            score >= 60 -> FAIR
            score >= 40 -> POOR
            else -> BAD
        }
    }
}
