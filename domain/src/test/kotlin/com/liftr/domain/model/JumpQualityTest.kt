package com.liftr.domain.model

import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.RepCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpQualityTest {

    private fun check(passed: Boolean) = JumpFitCheck(
        gravityEstimate = if (passed) 9.8f else 6f,
        gravityTStat = if (passed) 0.2f else 8f,
        gravityOk = passed,
        durbinWatson = 2f,
        lag1Autocorrelation = 0f,
        residualStep = false,
        residualsOk = true,
        rmsCm = 0.5f,
        samples = 20
    )

    @Test
    fun `quality comes from the fit check`() {
        assertEquals(JumpQuality.OK, JumpQuality.of(check(passed = true)))
        assertEquals(JumpQuality.SUSPECT, JumpQuality.of(check(passed = false)))
        assertEquals(JumpQuality.UNCHECKED, JumpQuality.of(null))
    }

    @Test
    fun `a failed timing check alone makes a jump suspect`() {
        assertEquals(JumpQuality.SUSPECT, JumpQuality.of(check(passed = true).copy(timingOk = false)))
    }

    @Test
    fun `rep jump data knows whether it is suspect`() {
        assertTrue(RepJumpData(40f, fitCheck = check(passed = false)).isSuspect)
        assertFalse(RepJumpData(40f, fitCheck = check(passed = true)).isSuspect)
        // Old rows and approach jumps have no check: unknown, not failed.
        assertFalse(RepJumpData(40f).isSuspect)
        assertEquals(JumpQuality.UNCHECKED, JumpRepRecord(0L, 1L, 1, RepJumpData(40f)).quality)
    }

    @Test
    fun `jumps that count leave out suspect ones but keep unchecked ones`() {
        val qualities = listOf(JumpQuality.OK, JumpQuality.SUSPECT, JumpQuality.UNCHECKED)

        assertEquals(listOf(JumpQuality.OK, JumpQuality.UNCHECKED), qualities.jumpsThatCount { it })
        assertEquals(1, qualities.excludedJumpCount { it })
        assertFalse(qualities.allJumpsSuspect { it })
    }

    @Test
    fun `when every jump is suspect all of them count`() {
        val qualities = listOf(JumpQuality.SUSPECT, JumpQuality.SUSPECT)

        assertEquals(qualities, qualities.jumpsThatCount { it })
        assertEquals(0, qualities.excludedJumpCount { it })
        assertTrue(qualities.allJumpsSuspect { it })
        assertFalse(emptyList<JumpQuality>().allJumpsSuspect { it })
    }

    private fun summary(heightsCm: List<Float>, apexCm: List<Float?>, qualities: List<JumpQuality>) = SetSummary(
        exerciseType = ExerciseType.VERTICAL_JUMP,
        weight = Kilograms(0f),
        targetReps = RepCount(heightsCm.size),
        completedReps = RepCount(heightsCm.size),
        repScores = emptyList(),
        repVelocities = heightsCm.map { MetersPerSecond(2.5f) },
        avgVelocity = MetersPerSecond(2.5f),
        peakVelocity = MetersPerSecond(2.5f),
        velocityLossPercent = 0f,
        overallScore = 0,
        bestRepIndex = 0,
        worstRepIndex = 0,
        aggregateFormIssues = emptyList(),
        repJumpHeights = heightsCm.map { Meters(it / 100f) },
        repApexHeights = apexCm.map { it?.let { cm -> Meters(cm / 100f) } },
        repJumpQualities = qualities
    )

    @Test
    fun `set best and average leave out suspect jumps`() {
        val s = summary(
            heightsCm = listOf(40f, 60f, 30f),
            apexCm = listOf(38f, 58f, null),
            qualities = listOf(JumpQuality.OK, JumpQuality.SUSPECT, JumpQuality.UNCHECKED)
        )

        assertEquals(0.40f, s.bestJumpHeight!!.value, 1e-4f)
        assertEquals(0.35f, s.avgJumpHeight!!.value, 1e-4f)
        assertEquals(0.38f, s.bestApexHeight!!.value, 1e-4f)
        assertEquals(0.38f, s.avgApexHeight!!.value, 1e-4f)
        assertEquals(1, s.excludedJumpCount)
        assertFalse(s.allJumpsSuspect)
        assertEquals(0.40f, s.recordJumpHeight!!.value, 1e-4f)
    }

    @Test
    fun `a set of only suspect jumps still shows its best, but makes no record`() {
        val s = summary(
            heightsCm = listOf(40f, 60f),
            apexCm = listOf(null, null),
            qualities = listOf(JumpQuality.SUSPECT, JumpQuality.SUSPECT)
        )

        assertEquals(0.60f, s.bestJumpHeight!!.value, 1e-4f)
        assertEquals(0.50f, s.avgJumpHeight!!.value, 1e-4f)
        assertTrue(s.allJumpsSuspect)
        assertEquals(0, s.excludedJumpCount)
        assertNull(s.recordJumpHeight)
    }

    @Test
    fun `a summary without qualities counts every jump`() {
        val s = summary(heightsCm = listOf(40f, 60f), apexCm = listOf(null, null), qualities = emptyList())

        assertEquals(0.60f, s.bestJumpHeight!!.value, 1e-4f)
        assertEquals(0.50f, s.avgJumpHeight!!.value, 1e-4f)
        assertEquals(0.60f, s.recordJumpHeight!!.value, 1e-4f)
        assertEquals(JumpQuality.UNCHECKED, s.jumpQuality(0))
    }
}
