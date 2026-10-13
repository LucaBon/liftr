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
 * @property totalScore Combined score (0-100)
 * @property depthScore Depth component (0-30)
 * @property barPathScore Bar path straightness component (0-20)
 * @property tempoScore Tempo control component (0-20)
 * @property formScore Form quality component (0-30)
 * @property depthAchieved Whether the required depth was reached
 * @property formIssues Form issues detected during this rep
 */
data class RepQualityScore(
    val totalScore: Int,
    val depthScore: Int,
    val barPathScore: Int,
    val tempoScore: Int,
    val formScore: Int,
    val depthAchieved: Boolean,
    val formIssues: List<FormIssue>
) {
    val grade: ScoreGrade get() = ScoreGrade.fromScore(totalScore)
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
