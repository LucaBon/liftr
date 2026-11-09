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

    @Test
    fun `scoreTempo does not judge a deadlift's lowering`() {
        // A deadlift rep ends at lockout; its lowering isn't timed (0 s) and may be a drop.
        val score = RepQualityScorer.scoreTempo(
            eccentricDuration = 0f,
            concentricDuration = 1.2f,
            previousConcentricDurations = listOf(1.2f),
            exerciseType = com.liftr.domain.model.ExerciseType.DEADLIFT
        )
        assertEquals(20, score)
    }

    @Test
    fun `scoreTempo still judges a squat's lowering`() {
        val score = RepQualityScorer.scoreTempo(
            eccentricDuration = 0.3f,
            concentricDuration = 1.0f,
            previousConcentricDurations = emptyList(),
            exerciseType = com.liftr.domain.model.ExerciseType.BACK_SQUAT
        )
        assertEquals(10, score)
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
    fun `scoreForm should not charge an unscored issue`() {
        val issues = listOf(FormIssue(Severity.INFO, "Chest pause", "", scored = false))
        assertEquals(30, RepQualityScorer.scoreForm(issues))
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

    @Test
    fun `scoreDepth grades an invalid depth by how many cm it fell short`() {
        fun score(cm: Float?) = RepQualityScorer.scoreDepth(
            DepthValidationResult(isValid = false, confidence = 0.2f, methods = emptyList(), shortfallCm = cm)
        )
        assertEquals(20, score(0f))
        assertEquals(15, score(3f))
        assertEquals(13, score(4f))
        assertEquals(0, score(12f))
        assertEquals(0, score(30f))
        // No shortfall measured: the old confidence-based credit.
        assertEquals(3, score(null))
    }

    @Test
    fun `scoreDepth never ranks an invalid depth above a valid one`() {
        val invalid = DepthValidationResult(isValid = false, confidence = 0.5f, methods = emptyList(), shortfallCm = 0f)
        val valid = DepthValidationResult(isValid = true, confidence = 0.6f, methods = emptyList())
        assertTrue(RepQualityScorer.scoreDepth(valid) >= RepQualityScorer.scoreDepth(invalid))
    }

    private fun score(type: com.liftr.domain.model.ExerciseType, xs: List<Float>) = RepQualityScorer.scoreRep(
        exerciseType = type,
        depthValidation = DepthValidationResult(isValid = true, confidence = 0.9f, methods = emptyList()),
        barXPositions = xs,
        eccentricDuration = 1.5f,
        concentricDuration = 1.0f,
        previousConcentricDurations = emptyList(),
        formIssues = emptyList()
    )

    @Test
    fun `an air squat has no bar path score and its other parts make up 100`() {
        // A swaying track that would cost a barbell squat its bar path points.
        val sway = listOf(0f, 0.2f, -0.2f, 0.2f, -0.2f)
        val air = score(com.liftr.domain.model.ExerciseType.AIR_SQUAT, sway)
        assertEquals(0, air.barPathScore)
        assertFalse(air.barPathApplicable)
        // depth 30 + tempo 20 + form 30 = 80 of 80.
        assertEquals(100, air.totalScore)

        val back = score(com.liftr.domain.model.ExerciseType.BACK_SQUAT, sway)
        assertTrue(back.barPathApplicable)
        assertEquals(0, back.barPathScore)
        assertEquals(80, back.totalScore)
    }

    @Test
    fun `an air squat total is rescaled from 80 and rounded`() {
        val air = RepQualityScorer.scoreRep(
            exerciseType = com.liftr.domain.model.ExerciseType.AIR_SQUAT,
            depthValidation = DepthValidationResult(isValid = false, confidence = 0.2f, methods = emptyList(), shortfallCm = 4f),
            barXPositions = emptyList(),
            eccentricDuration = 1.5f,
            concentricDuration = 1.0f,
            previousConcentricDurations = emptyList(),
            formIssues = emptyList()
        )
        // depth 13 + tempo 20 + form 30 = 63 of 80 = 78.75 -> 79.
        assertEquals(13, air.depthScore)
        assertEquals(79, air.totalScore)
    }
}
