package com.liftr.domain.exercise

import com.liftr.domain.model.SquatDepthType
import com.liftr.domain.value.Meters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KneeAngleDepthTest {

    /** Positions in meters; larger y = lower, side view. */
    private fun state(hip: Pair<Float, Float>, knee: Pair<Float, Float>, ankle: Pair<Float, Float>?) =
        ExercisePhaseState.initial().withCurrentPositions(
            headY = null, shouldersY = null, hipsY = hip.second, kneesY = knee.second, hipsX = hip.first, kneesX = knee.first
        ).withAnklePositions(ankle?.second, ankle?.first)

    private fun validate(s: ExercisePhaseState, type: SquatDepthType = SquatDepthType.POWERLIFTING) =
        SquatDepthValidator.validateDepth(s, type, Meters(0.02f))

    // A real rep at the limit of the movement: hip 2 cm above the knee, knee closed to about 53°.
    private val closed = state(hip = 0.70f to 1.69f, knee = 0.31f to 1.71f, ankle = 0.58f to 2.05f)

    @Test
    fun `a closed knee reaches depth with the hip landmark above the knee`() {
        val result = validate(closed)

        assertTrue(result.debugInfo, result.isValid)
        assertEquals(0.85f, result.confidence, 0.001f)
    }

    @Test
    fun `an open knee just above parallel stays short of depth`() {
        val result = validate(state(hip = 0.70f to 1.60f, knee = 0.31f to 1.71f, ankle = 0.20f to 2.10f))

        assertFalse(result.debugInfo, result.isValid)
    }

    @Test
    fun `without ankles the verdict is the height checks alone`() {
        val noAnkle = state(hip = 0.70f to 1.69f, knee = 0.31f to 1.71f, ankle = null)

        assertFalse(validate(noAnkle).isValid)
    }

    @Test
    fun `a collapsed leg is not a closed knee`() {
        val collapsed = state(hip = 0.33f to 1.63f, knee = 0.31f to 1.71f, ankle = 0.33f to 1.80f)

        assertFalse(validate(collapsed).isValid)
    }

    @Test
    fun `a knee angle says nothing against a maximum depth standard`() {
        assertTrue(validate(closed, SquatDepthType.QUARTER).methods.none { it.name == "Knee Angle" })
    }
}
