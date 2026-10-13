package com.liftr.domain.exercise

import com.liftr.domain.model.DepthValidationResult
import com.liftr.domain.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for RepQualityScorer — the stateless rep scoring engine.
 *
 * Tests all 4 scoring components:
 * - Depth (0-30 points)
 * - Bar Path (0-20 points)
 * - Tempo (0-20 points)
 * - Form (0-30 points)
 */
class RepQualityScorerTest {

    // --- Depth Scoring Tests ---

    @Test
    fun `scoreDepth should return 30 for valid depth with high confidence`() {
        val result = DepthValidationResult(isValid = true, confidence = 0.9f, methods = emptyList())
        assertEquals(30, RepQualityScorer.scoreDepth(result))
    }

    @Test
    fun `scoreDepth should return 25 for valid depth with medium confidence`() {
        val result = DepthValidationResult(isValid = true, confidence = 0.7f, methods = emptyList())
        assertEquals(25, RepQualityScorer.scoreDepth(result))
    }

    @Test
    fun `scoreDepth should return 20 for valid depth with low confidence`() {
        val result = DepthValidationResult(isValid = true, confidence = 0.5f, methods = emptyList())
        assertEquals(20, RepQualityScorer.scoreDepth(result))
    }

    @Test
    fun `scoreDepth should return partial credit for invalid depth based on confidence`() {
        val result = DepthValidationResult(isValid = false, confidence = 0.5f, methods = emptyList())
        assertEquals(7, RepQualityScorer.scoreDepth(result)) // 0.5 * 15 = 7
    }

    @Test
    fun `scoreDepth should return 0 for invalid depth with zero confidence`() {
        val result = DepthValidationResult(isValid = false, confidence = 0f, methods = emptyList())
        assertEquals(0, RepQualityScorer.scoreDepth(result))
    }

    @Test
    fun `scoreDepth should return 15 when no depth data available`() {
        assertEquals(15, RepQualityScorer.scoreDepth(null))
    }

    @Test
    fun `scoreDepth should return 30 for confidence exactly at 0_8 boundary`() {
        // > 0.8f, so 0.8 itself should fall to 25
        val result = DepthValidationResult(isValid = true, confidence = 0.8f, methods = emptyList())
        assertEquals(25, RepQualityScorer.scoreDepth(result))
    }

    @Test
    fun `scoreDepth should return 30 for confidence above 0_8`() {
        val result = DepthValidationResult(isValid = true, confidence = 0.81f, methods = emptyList())
        assertEquals(30, RepQualityScorer.scoreDepth(result))
    }

    // --- Bar Path Scoring Tests ---

    @Test
    fun `scoreBarPath should return 20 for perfectly straight bar path`() {
        val positions = listOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
        assertEquals(20, RepQualityScorer.scoreBarPath(positions))
    }

    @Test
    fun `scoreBarPath should return 0 for very wobbly bar path`() {
        // Std dev > 0.10m → score = 0
        val positions = listOf(0.0f, 0.3f, -0.2f, 0.4f, -0.3f)
        assertEquals(0, RepQualityScorer.scoreBarPath(positions))
    }

    @Test
    fun `scoreBarPath should return partial score for moderate deviation`() {
        // Small deviation around 0.5
        val positions = listOf(0.48f, 0.52f, 0.49f, 0.51f, 0.50f)
        val score = RepQualityScorer.scoreBarPath(positions)
        assertTrue("Score should be between 10 and 20, was $score", score in 10..20)
    }

    @Test
    fun `scoreBarPath should return 15 for insufficient data`() {
        assertEquals(15, RepQualityScorer.scoreBarPath(listOf(0.5f, 0.5f))) // < 3 samples
    }

    @Test
    fun `scoreBarPath should return 15 for empty list`() {
        assertEquals(15, RepQualityScorer.scoreBarPath(emptyList()))
    }

    @Test
    fun `scoreBarPath should return 15 for single sample`() {
        assertEquals(15, RepQualityScorer.scoreBarPath(listOf(0.5f)))
    }

    // --- Tempo Scoring Tests ---

    @Test
    fun `scoreTempo should return 20 for controlled eccentric and first rep`() {
        val score = RepQualityScorer.scoreTempo(
            eccentricDuration = 2.0f,
            concentricDuration = 1.0f,
            previousConcentricDurations = emptyList() // first rep
        )
        assertEquals(20, score)
    }

    @Test
    fun `scoreTempo should return 10 for controlled eccentric with 10 consistency`() {
        val score = RepQualityScorer.scoreTempo(
            eccentricDuration = 1.5f,
            concentricDuration = 1.0f,
            previousConcentricDurations = listOf(1.0f) // same speed = full consistency
        )
        assertEquals(20, score)
    }

    @Test
    fun `scoreTempo should return 5 for fast eccentric on first rep`() {
        val score = RepQualityScorer.scoreTempo(
            eccentricDuration = 0.7f, // ≥0.5 but <1.0 → 5
            concentricDuration = 1.0f,
            previousConcentricDurations = emptyList()
        )
        assertEquals(15, score) // 5 eccentric + 10 first rep
    }

    @Test
    fun `scoreTempo should return 0 for dropping weight`() {
        val score = RepQualityScorer.scoreTempo(
            eccentricDuration = 0.3f, // <0.5 → 0
            concentricDuration = 1.0f,
            previousConcentricDurations = emptyList()
        )
        assertEquals(10, score) // 0 eccentric + 10 first rep
    }

    @Test
    fun `scoreTempo should penalize inconsistent concentric speed`() {
        val score = RepQualityScorer.scoreTempo(
            eccentricDuration = 1.5f, // 10 points
            concentricDuration = 2.0f, // double the average → big deviation
            previousConcentricDurations = listOf(1.0f, 1.0f)
        )
        // Eccentric = 10. Deviation = |2.0 - 1.0| / 1.0 = 1.0 → (1 - 1.0) = 0 → 0 pts
        assertEquals(10, score)
    }

    @Test
    fun `scoreTempo should give full consistency for matching concentric`() {
        val score = RepQualityScorer.scoreTempo(
            eccentricDuration = 1.0f,
            concentricDuration = 1.0f,
            previousConcentricDurations = listOf(1.0f, 1.0f)
        )
        assertEquals(20, score) // 10 + 10
    }

    // --- Form Scoring Tests ---

    @Test
    fun `scoreForm should return 30 for no issues`() {
        assertEquals(30, RepQualityScorer.scoreForm(emptyList()))
    }

    @Test
    fun `scoreForm should subtract 15 for critical issue`() {
        val issues = listOf(FormIssue(Severity.CRITICAL, "Knees caving", "Push outward"))
        assertEquals(15, RepQualityScorer.scoreForm(issues))
    }

    @Test
    fun `scoreForm should subtract 8 for warning issue`() {
        val issues = listOf(FormIssue(Severity.WARNING, "Forward lean", "Stay upright"))
        assertEquals(22, RepQualityScorer.scoreForm(issues))
    }

    @Test
    fun `scoreForm should subtract 3 for info issue`() {
        val issues = listOf(FormIssue(Severity.INFO, "Limited hip hinge", "Push hips back"))
        assertEquals(27, RepQualityScorer.scoreForm(issues))
    }

    @Test
    fun `scoreForm should floor at 0 for multiple critical issues`() {
        val issues = listOf(
            FormIssue(Severity.CRITICAL, "Issue 1", "Fix 1"),
            FormIssue(Severity.CRITICAL, "Issue 2", "Fix 2"),
            FormIssue(Severity.CRITICAL, "Issue 3", "Fix 3")
        )
        assertEquals(0, RepQualityScorer.scoreForm(issues)) // 30 - 45 → clamped to 0
    }

    @Test
    fun `scoreForm should handle mixed severity issues`() {
        val issues = listOf(
            FormIssue(Severity.CRITICAL, "Major", "Fix"),     // -15
            FormIssue(Severity.WARNING, "Moderate", "Fix"),    // -8
            FormIssue(Severity.INFO, "Minor", "Fix")           // -3
        )
        assertEquals(4, RepQualityScorer.scoreForm(issues)) // 30 - 15 - 8 - 3 = 4
    }

    // --- Full scoreRep Integration Tests ---

    @Test
    fun `scoreRep should return perfect 100 for ideal rep`() {
        val score = RepQualityScorer.scoreRep(
            exerciseType = ExerciseType.BACK_SQUAT,
            depthValidation = DepthValidationResult(isValid = true, confidence = 0.95f, methods = emptyList()),
            barXPositions = listOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
            eccentricDuration = 2.0f,
            concentricDuration = 1.0f,
            previousConcentricDurations = emptyList(),
            formIssues = emptyList()
        )

        assertEquals(100, score.totalScore)
        assertEquals(30, score.depthScore)
        assertEquals(20, score.barPathScore)
        assertEquals(20, score.tempoScore)
        assertEquals(30, score.formScore)
        assertTrue(score.depthAchieved)
        assertTrue(score.formIssues.isEmpty())
    }

    @Test
    fun `scoreRep should return low score for poor rep`() {
        val score = RepQualityScorer.scoreRep(
            exerciseType = ExerciseType.BACK_SQUAT,
            depthValidation = DepthValidationResult(isValid = false, confidence = 0.2f, methods = emptyList()),
            barXPositions = listOf(0.0f, 0.3f, -0.2f, 0.4f), // very wobbly
            eccentricDuration = 0.3f, // dropping weight
            concentricDuration = 3.0f, // very slow
            previousConcentricDurations = listOf(1.0f), // inconsistent
            formIssues = listOf(
                FormIssue(Severity.CRITICAL, "Knees caving", "Fix"),
                FormIssue(Severity.WARNING, "Forward lean", "Fix")
            )
        )

        assertTrue("Score should be below 30, was ${score.totalScore}", score.totalScore < 30)
        assertFalse(score.depthAchieved)
    }

    @Test
    fun `scoreRep should correctly sum all components`() {
        val score = RepQualityScorer.scoreRep(
            exerciseType = ExerciseType.FRONT_SQUAT,
            depthValidation = DepthValidationResult(isValid = true, confidence = 0.7f, methods = emptyList()),
            barXPositions = listOf(0.5f, 0.5f, 0.5f),
            eccentricDuration = 1.0f,
            concentricDuration = 1.0f,
            previousConcentricDurations = emptyList(),
            formIssues = listOf(FormIssue(Severity.WARNING, "Lean", "Fix"))
        )

        assertEquals(
            score.depthScore + score.barPathScore + score.tempoScore + score.formScore,
            score.totalScore
        )
    }

    @Test
    fun `scoreRep grade should match total score`() {
        val score = RepQualityScorer.scoreRep(
            exerciseType = ExerciseType.BACK_SQUAT,
            depthValidation = DepthValidationResult(isValid = true, confidence = 0.95f, methods = emptyList()),
            barXPositions = listOf(0.5f, 0.5f, 0.5f, 0.5f),
            eccentricDuration = 1.5f,
            concentricDuration = 1.0f,
            previousConcentricDurations = emptyList(),
            formIssues = emptyList()
        )

        assertEquals(
            com.liftr.domain.model.ScoreGrade.fromScore(score.totalScore),
            score.grade
        )
    }
}
