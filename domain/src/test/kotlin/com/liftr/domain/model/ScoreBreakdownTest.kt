package com.liftr.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreBreakdownTest {

    @Test
    fun `averages each component over the reps`() {
        val breakdown = ScoreBreakdown.of(
            listOf(RepScoreParts(30, 10, 20, 0), RepScoreParts(20, 15, 10, 15))
        )

        assertEquals(ScoreBreakdown(depth = 25f, barPath = 12.5f, tempo = 15f, form = 7.5f, repCount = 2), breakdown)
    }

    @Test
    fun `nothing to average gives null`() {
        assertNull(ScoreBreakdown.of(emptyList()))
    }

    @Test
    fun `a rep score exposes its parts`() {
        val score = RepQualityScore(55, 30, 12, 13, 0, depthAchieved = true, formIssues = emptyList())

        assertEquals(RepScoreParts(depth = 30, barPath = 12, tempo = 13, form = 0), score.parts)
    }
}
