package com.liftr.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for ScoreGrade boundary values.
 */
class ScoreGradeTest {

    @Test
    fun `fromScore should return EXCELLENT for 100`() {
        assertEquals(ScoreGrade.EXCELLENT, ScoreGrade.fromScore(100))
    }

    @Test
    fun `fromScore should return EXCELLENT for 90`() {
        assertEquals(ScoreGrade.EXCELLENT, ScoreGrade.fromScore(90))
    }

    @Test
    fun `fromScore should return GOOD for 89`() {
        assertEquals(ScoreGrade.GOOD, ScoreGrade.fromScore(89))
    }

    @Test
    fun `fromScore should return GOOD for 75`() {
        assertEquals(ScoreGrade.GOOD, ScoreGrade.fromScore(75))
    }

    @Test
    fun `fromScore should return FAIR for 74`() {
        assertEquals(ScoreGrade.FAIR, ScoreGrade.fromScore(74))
    }

    @Test
    fun `fromScore should return FAIR for 60`() {
        assertEquals(ScoreGrade.FAIR, ScoreGrade.fromScore(60))
    }

    @Test
    fun `fromScore should return POOR for 59`() {
        assertEquals(ScoreGrade.POOR, ScoreGrade.fromScore(59))
    }

    @Test
    fun `fromScore should return POOR for 40`() {
        assertEquals(ScoreGrade.POOR, ScoreGrade.fromScore(40))
    }

    @Test
    fun `fromScore should return BAD for 39`() {
        assertEquals(ScoreGrade.BAD, ScoreGrade.fromScore(39))
    }

    @Test
    fun `fromScore should return BAD for 0`() {
        assertEquals(ScoreGrade.BAD, ScoreGrade.fromScore(0))
    }

    @Test
    fun `each grade should have a non-empty label`() {
        ScoreGrade.entries.forEach { grade ->
            assert(grade.label.isNotBlank()) { "Grade ${grade.name} has blank label" }
        }
    }
}
