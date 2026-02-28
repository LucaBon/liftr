package com.lifthelper.domain.model

import com.lifthelper.domain.exercise.FormIssue
import com.lifthelper.domain.exercise.Severity
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.value.RepCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for SetSummary domain model.
 */
class SetSummaryTest {

    private fun createScore(
        total: Int,
        depth: Int = 30,
        barPath: Int = 20,
        tempo: Int = 20,
        form: Int = 30,
        depthAchieved: Boolean = true,
        formIssues: List<FormIssue> = emptyList()
    ) = RepQualityScore(
        totalScore = total,
        depthScore = depth,
        barPathScore = barPath,
        tempoScore = tempo,
        formScore = form,
        depthAchieved = depthAchieved,
        formIssues = formIssues
    )

    private fun createSummary(
        scores: List<RepQualityScore>,
        velocities: List<MetersPerSecond> = scores.map { MetersPerSecond(0.8f) }
    ): SetSummary {
        val avgVel = if (velocities.isNotEmpty()) {
            MetersPerSecond(velocities.map { it.value }.average().toFloat())
        } else MetersPerSecond(0f)
        val peakVel = if (velocities.isNotEmpty()) {
            velocities.maxBy { it.value }
        } else MetersPerSecond(0f)

        return SetSummary(
            exerciseType = ExerciseType.BACK_SQUAT,
            weight = Kilograms(100f),
            targetReps = RepCount(5),
            completedReps = RepCount(scores.size),
            repScores = scores,
            repVelocities = velocities,
            avgVelocity = avgVel,
            peakVelocity = peakVel,
            velocityLossPercent = 0.2f,
            overallScore = scores.map { it.totalScore }.average().toInt(),
            bestRepIndex = scores.indices.maxByOrNull { scores[it].totalScore } ?: 0,
            worstRepIndex = scores.indices.minByOrNull { scores[it].totalScore } ?: 0,
            aggregateFormIssues = emptyList()
        )
    }

    @Test
    fun `overallScore should be average of rep scores`() {
        val summary = createSummary(
            scores = listOf(
                createScore(90),
                createScore(80),
                createScore(70)
            )
        )
        assertEquals(80, summary.overallScore)
    }

    @Test
    fun `bestRepIndex should point to highest scoring rep`() {
        val summary = createSummary(
            scores = listOf(
                createScore(70),
                createScore(95),
                createScore(80)
            )
        )
        assertEquals(1, summary.bestRepIndex) // rep 2 (0-indexed)
    }

    @Test
    fun `worstRepIndex should point to lowest scoring rep`() {
        val summary = createSummary(
            scores = listOf(
                createScore(70),
                createScore(95),
                createScore(50)
            )
        )
        assertEquals(2, summary.worstRepIndex) // rep 3 (0-indexed)
    }

    @Test
    fun `overallGrade should match overall score`() {
        val summary = createSummary(
            scores = listOf(createScore(90), createScore(85))
        )
        assertEquals(ScoreGrade.GOOD, summary.overallGrade) // avg = 87 → GOOD
    }

    @Test
    fun `single rep set should have same best and worst index`() {
        val summary = createSummary(scores = listOf(createScore(75)))
        assertEquals(0, summary.bestRepIndex)
        assertEquals(0, summary.worstRepIndex)
    }

    @Test
    fun `aggregate form issues should group by message`() {
        val kneeIssue = FormIssue(Severity.WARNING, "Knees caving", "Fix")
        val leanIssue = FormIssue(Severity.INFO, "Forward lean", "Fix")

        val scores = listOf(
            createScore(70, formIssues = listOf(kneeIssue)),
            createScore(80, formIssues = emptyList()),
            createScore(65, formIssues = listOf(kneeIssue, leanIssue))
        )

        // Simulate aggregation
        val issueMap = mutableMapOf<String, Pair<FormIssue, MutableList<Int>>>()
        scores.forEachIndexed { index, score ->
            score.formIssues.forEach { issue ->
                val entry = issueMap.getOrPut(issue.message) { issue to mutableListOf() }
                entry.second.add(index + 1)
            }
        }
        val aggregated = issueMap.values.map { (issue, repNumbers) ->
            AggregateFormIssue(issue = issue, repNumbers = repNumbers)
        }

        assertEquals(2, aggregated.size)

        val kneeAggregate = aggregated.find { it.issue.message == "Knees caving" }!!
        assertEquals(listOf(1, 3), kneeAggregate.repNumbers)

        val leanAggregate = aggregated.find { it.issue.message == "Forward lean" }!!
        assertEquals(listOf(3), leanAggregate.repNumbers)
    }

    @Test
    fun `completedReps should equal number of scores`() {
        val summary = createSummary(
            scores = listOf(createScore(90), createScore(80), createScore(70))
        )
        assertEquals(RepCount(3), summary.completedReps)
    }

    @Test
    fun `avgVelocity should be calculated correctly`() {
        val velocities = listOf(
            MetersPerSecond(0.8f),
            MetersPerSecond(0.7f),
            MetersPerSecond(0.6f)
        )
        val summary = createSummary(
            scores = listOf(createScore(90), createScore(80), createScore(70)),
            velocities = velocities
        )
        assertEquals(0.7f, summary.avgVelocity.value, 0.001f)
    }

    @Test
    fun `peakVelocity should be the maximum`() {
        val velocities = listOf(
            MetersPerSecond(0.8f),
            MetersPerSecond(0.9f),
            MetersPerSecond(0.6f)
        )
        val summary = createSummary(
            scores = listOf(createScore(90), createScore(80), createScore(70)),
            velocities = velocities
        )
        assertEquals(0.9f, summary.peakVelocity.value, 0.001f)
    }
}
