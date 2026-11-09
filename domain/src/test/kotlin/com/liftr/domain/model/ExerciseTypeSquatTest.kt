package com.liftr.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseTypeSquatTest {
    @Test
    fun `an air squat is a squat with no barbell`() {
        assertTrue(ExerciseType.AIR_SQUAT.isSquat())
        assertFalse(ExerciseType.AIR_SQUAT.tracksBarPlate())
        assertFalse(ExerciseType.AIR_SQUAT.hasExternalLoad())
        assertTrue(ExerciseType.BACK_SQUAT.isSquat() && ExerciseType.FRONT_SQUAT.isSquat())
        assertFalse(ExerciseType.BENCH_PRESS.isSquat())
    }
}
