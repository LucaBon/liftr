package com.liftr.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseTypeCameraViewTest {

    private fun standing(type: ExerciseType) = MovementPhase.entries.filter { type.isStandingPhase(it) }.toSet()

    @Test
    fun `lifts from the floor are standing only at the top`() {
        for (type in listOf(ExerciseType.DEADLIFT, ExerciseType.CLEAN, ExerciseType.SNATCH)) {
            assertEquals(type.name, setOf(MovementPhase.TOP), standing(type))
        }
    }

    @Test
    fun `squats stand between reps and at the top`() {
        assertEquals(setOf(MovementPhase.IDLE, MovementPhase.TOP), standing(ExerciseType.BACK_SQUAT))
        assertEquals(setOf(MovementPhase.IDLE, MovementPhase.TOP), standing(ExerciseType.FRONT_SQUAT))
    }

    @Test
    fun `jumps stand only at rest`() {
        assertEquals(setOf(MovementPhase.IDLE), standing(ExerciseType.VERTICAL_JUMP))
    }

    @Test
    fun `only the bench press view can't be judged`() {
        assertFalse(ExerciseType.BENCH_PRESS.canJudgeCameraView())
        assertTrue(ExerciseType.entries.filter { it != ExerciseType.BENCH_PRESS }.all { it.canJudgeCameraView() })
    }

    @Test
    fun `only the deadlift rep starts with the lift`() {
        assertEquals(listOf(ExerciseType.DEADLIFT), ExerciseType.entries.filter { it.startsWithConcentric() })
    }
}
