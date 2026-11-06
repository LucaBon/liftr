package com.liftr.vision.camera

import com.liftr.domain.model.ExerciseType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighSpeedAvailabilityTest {

    @Test
    fun `fast movement without high speed warns`() {
        assertTrue(shouldWarnHighSpeedUnavailable(ExerciseType.CLEAN, null))
        assertTrue(shouldWarnHighSpeedUnavailable(ExerciseType.SNATCH, 60))
    }

    @Test
    fun `fast movement with high speed does not warn`() {
        assertFalse(shouldWarnHighSpeedUnavailable(ExerciseType.CLEAN, 120))
        assertFalse(shouldWarnHighSpeedUnavailable(ExerciseType.SNATCH, 240))
    }

    @Test
    fun `slow lifts never warn`() {
        assertFalse(shouldWarnHighSpeedUnavailable(ExerciseType.FRONT_SQUAT, null))
    }
}
