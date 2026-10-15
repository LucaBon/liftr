package com.liftr.domain.calculator

import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CenterOfMassCalculatorTest {

    /** Frontal standing pose, arms hanging, symmetric about x = 0.5. */
    private fun standing(
        armsUp: Boolean = false,
        dy: Float = 0f,
        hidden: Set<Int> = emptySet()
    ): PoseResult {
        val points = HashMap<Int, Pair<Float, Float>>()
        fun both(left: Int, right: Int, halfWidth: Float, y: Float) {
            points[left] = 0.5f + halfWidth to y
            points[right] = 0.5f - halfWidth to y
        }
        points[PoseResult.NOSE] = 0.5f to 0.11f
        both(PoseResult.LEFT_EAR, PoseResult.RIGHT_EAR, 0.03f, 0.10f)
        both(PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER, 0.10f, 0.25f)
        if (armsUp) {
            both(PoseResult.LEFT_ELBOW, PoseResult.RIGHT_ELBOW, 0.10f, 0.10f)
            both(PoseResult.LEFT_WRIST, PoseResult.RIGHT_WRIST, 0.10f, -0.02f)
            both(PoseResult.LEFT_INDEX, PoseResult.RIGHT_INDEX, 0.10f, -0.06f)
            both(PoseResult.LEFT_PINKY, PoseResult.RIGHT_PINKY, 0.10f, -0.06f)
        } else {
            both(PoseResult.LEFT_ELBOW, PoseResult.RIGHT_ELBOW, 0.11f, 0.40f)
            both(PoseResult.LEFT_WRIST, PoseResult.RIGHT_WRIST, 0.11f, 0.52f)
            both(PoseResult.LEFT_INDEX, PoseResult.RIGHT_INDEX, 0.11f, 0.56f)
            both(PoseResult.LEFT_PINKY, PoseResult.RIGHT_PINKY, 0.11f, 0.56f)
        }
        both(PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP, 0.06f, 0.52f)
        both(PoseResult.LEFT_KNEE, PoseResult.RIGHT_KNEE, 0.06f, 0.72f)
        both(PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE, 0.06f, 0.92f)
        both(PoseResult.LEFT_HEEL, PoseResult.RIGHT_HEEL, 0.06f, 0.94f)
        both(PoseResult.LEFT_FOOT_INDEX, PoseResult.RIGHT_FOOT_INDEX, 0.07f, 0.95f)

        val landmarks = List(33) { i ->
            val p = points[i]
            if (p == null || i in hidden) PoseKeypoint(0f, 0f, 0f, 0f)
            else PoseKeypoint(p.first, p.second + dy, 0f, 1f)
        }
        return PoseResult(landmarks, 0L, 540, 960, true)
    }

    @Test
    fun `symmetric pose has center of mass on the midline`() {
        val (x, _) = CenterOfMassCalculator.centerOfMass(standing())!!
        assertEquals(0.5f, x, 1e-5f)
    }

    @Test
    fun `standing center of mass sits just above the hips`() {
        val (_, y) = CenterOfMassCalculator.centerOfMass(standing())!!
        // Between shoulders (0.25) and hips (0.52), in the lower part of the trunk.
        assertTrue("y=$y", y in 0.42f..0.52f)
    }

    @Test
    fun `raising the arms raises the center of mass`() {
        val (_, down) = CenterOfMassCalculator.centerOfMass(standing())!!
        val (_, up) = CenterOfMassCalculator.centerOfMass(standing(armsUp = true))!!
        // Arms are 10% of body mass; their combined center moves from
        // y=0.386 (hanging) to y=0.114 (overhead), a rise of 0.272.
        assertEquals(0.0272f, down - up, 0.0005f)
    }

    @Test
    fun `moving the whole body moves the center of mass by the same amount`() {
        val (_, y0) = CenterOfMassCalculator.centerOfMass(standing())!!
        val (_, y1) = CenterOfMassCalculator.centerOfMass(standing(dy = -0.05f))!!
        assertEquals(-0.05f, y1 - y0, 1e-5f)
    }

    @Test
    fun `hidden hands and feet fall back to wrists and ankles`() {
        val hidden = setOf(
            PoseResult.LEFT_INDEX, PoseResult.RIGHT_INDEX, PoseResult.LEFT_PINKY, PoseResult.RIGHT_PINKY,
            PoseResult.LEFT_HEEL, PoseResult.RIGHT_HEEL, PoseResult.LEFT_FOOT_INDEX, PoseResult.RIGHT_FOOT_INDEX
        )
        val full = CenterOfMassCalculator.centerOfMass(standing())!!
        val partial = CenterOfMassCalculator.centerOfMass(standing(hidden = hidden))
        assertNotNull(partial)
        assertEquals(full.second, partial!!.second, 0.005f)
    }

    @Test
    fun `core joint missing on both sides gives no center of mass`() {
        assertNull(
            CenterOfMassCalculator.centerOfMass(
                standing(hidden = setOf(PoseResult.LEFT_KNEE, PoseResult.RIGHT_KNEE))
            )
        )
    }

    @Test
    fun `hidden far side borrows the near side, as when filmed side-on`() {
        val farSide = (PoseResult.LEFT_SHOULDER..PoseResult.LEFT_FOOT_INDEX step 2).toSet()
        val full = CenterOfMassCalculator.centerOfMass(standing())!!
        val nearOnly = CenterOfMassCalculator.centerOfMass(standing(hidden = farSide))
        assertNotNull(nearOnly)
        assertEquals(full.second, nearOnly!!.second, 0.001f)
    }

    @Test
    fun `load held in the hands moves the center of mass toward them`() {
        val (_, body) = CenterOfMassCalculator.centerOfMass(standing())!!
        val (_, loaded) = CenterOfMassCalculator.centerOfMass(standing(), extraMassFraction = 0.5f)!!
        // Dumbbells hang at the grip: halfway from the wrist (0.52) to the knuckles (0.56).
        val grip = 0.52f + 0.506f * 0.04f
        assertEquals((body + 0.5f * grip) / 1.5f, loaded, 1e-5f)
    }

    @Test
    fun `load falls back to the shoulders when no hand is visible`() {
        val hidden = setOf(
            PoseResult.LEFT_WRIST, PoseResult.RIGHT_WRIST, PoseResult.LEFT_INDEX, PoseResult.RIGHT_INDEX,
            PoseResult.LEFT_PINKY, PoseResult.RIGHT_PINKY
        )
        val (_, body) = CenterOfMassCalculator.centerOfMass(standing(hidden = hidden))!!
        val (_, loaded) = CenterOfMassCalculator.centerOfMass(standing(hidden = hidden), extraMassFraction = 0.5f)!!
        assertEquals((body + 0.5f * 0.25f) / 1.5f, loaded, 1e-5f)
    }

    @Test
    fun `load fraction needs a known body weight`() {
        assertEquals(0.5f, CenterOfMassCalculator.loadFraction(40f, 80f), 1e-6f)
        assertEquals(0f, CenterOfMassCalculator.loadFraction(40f, null), 0f)
        assertEquals(0f, CenterOfMassCalculator.loadFraction(0f, 80f), 0f)
    }
}
