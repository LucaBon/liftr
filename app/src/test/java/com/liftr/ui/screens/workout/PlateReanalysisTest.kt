package com.liftr.ui.screens.workout

import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.Set
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.RepCount
import org.junit.Assert.assertEquals
import org.junit.Test

class PlateReanalysisTest {

    private fun set(timestamp: Long, velocity: Float = 0.5f) = Set(
        workoutId = 0,
        exerciseType = ExerciseType.DEADLIFT,
        weight = Kilograms(100f),
        targetReps = RepCount(5),
        completedReps = RepCount(5),
        avgVelocity = MetersPerSecond(velocity),
        peakVelocity = MetersPerSecond(velocity),
        timestamp = timestamp
    )

    @Test
    fun `a new analysis is appended`() {
        val update = applyAnalyzedSet(listOf(set(1)), set(2), reanalysis = null)
        assertEquals(AnalyzedSetAction.APPEND, update.action)
        assertEquals(listOf(1L, 2L), update.sets.map { it.timestamp })
    }

    @Test
    fun `a re-analysis replaces its set in place without growing the session`() {
        val sets = listOf(set(1), set(2, 0.3f), set(3))
        val update = applyAnalyzedSet(sets, set(9, 0.6f), PlateReanalysis("raw.mp4", setKey = 2))
        assertEquals(AnalyzedSetAction.REPLACE, update.action)
        assertEquals(listOf(1L, 9L, 3L), update.sets.map { it.timestamp })
        assertEquals(0.6f, update.sets[1].avgVelocity.value, 0f)
    }

    @Test
    fun `a re-analysis whose set is gone changes nothing`() {
        val sets = listOf(set(1))
        val update = applyAnalyzedSet(sets, set(9), PlateReanalysis("raw.mp4", setKey = 2))
        assertEquals(AnalyzedSetAction.SKIP, update.action)
        assertEquals(sets, update.sets)
    }

    @Test
    fun `a re-analysis of an analysis that saved no set is appended`() {
        val update = applyAnalyzedSet(emptyList(), set(9), PlateReanalysis("raw.mp4", setKey = null))
        assertEquals(AnalyzedSetAction.APPEND, update.action)
        assertEquals(1, update.sets.size)
    }
}
