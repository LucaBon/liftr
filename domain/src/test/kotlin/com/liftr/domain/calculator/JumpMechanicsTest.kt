package com.liftr.domain.calculator

import com.liftr.domain.model.JumpExcursion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Samozino push-off mechanics and the push-off distance from a jump's excursion. */
class JumpMechanicsTest {

    @Test
    fun `force velocity and power match hand-computed values`() {
        // F = 75·9.81·(0.40/0.40 + 1) = 1471.5 N; v = √(9.81·0.40/2) = 1.4007 m/s; P = F·v
        val m = JumpMechanics.of(jumpHeightM = 0.40f, pushOffM = 0.40f, systemMassKg = 75f)!!

        assertEquals(1471.5f, m.meanForceN, 0.01f)
        assertEquals(1.4007f, m.meanVelocity, 0.0001f)
        assertEquals(2061.1f, m.meanPowerW, 0.2f)
        assertEquals(0.40f, m.pushOffM, 0f)
        assertEquals(75f, m.systemMassKg, 0f)
    }

    @Test
    fun `relative force and power are per kilogram of system mass`() {
        val m = JumpMechanics.of(jumpHeightM = 0.40f, pushOffM = 0.40f, systemMassKg = 75f)!!

        assertEquals(19.62f, m.relativeForce, 0.001f)
        assertEquals(27.48f, m.relativePower, 0.01f)
    }

    @Test
    fun `shorter push-off for the same jump needs more force at the same velocity`() {
        // F = 80·9.81·(0.30/0.25 + 1) = 1726.56 N; v = √(9.81·0.30/2) = 1.2130 m/s
        val m = JumpMechanics.of(jumpHeightM = 0.30f, pushOffM = 0.25f, systemMassKg = 80f)!!

        assertEquals(1726.56f, m.meanForceN, 0.01f)
        assertEquals(1.2130f, m.meanVelocity, 0.0001f)
        assertEquals(1726.56f * 1.2130f, m.meanPowerW, 0.5f)
    }

    @Test
    fun `push-off outside the plausible range gives nothing`() {
        assertNull(JumpMechanics.of(0.40f, pushOffM = 0.09f, systemMassKg = 75f))
        assertNull(JumpMechanics.of(0.40f, pushOffM = 0.71f, systemMassKg = 75f))
        assertNull(JumpMechanics.of(0.40f, pushOffM = 0f, systemMassKg = 75f))
        assertNotNull(JumpMechanics.of(0.40f, pushOffM = 0.10f, systemMassKg = 75f))
        assertNotNull(JumpMechanics.of(0.40f, pushOffM = 0.70f, systemMassKg = 75f))
    }

    @Test
    fun `no mass or no jump gives nothing`() {
        assertNull(JumpMechanics.of(0.40f, 0.40f, systemMassKg = 0f))
        assertNull(JumpMechanics.of(0f, 0.40f, systemMassKg = 75f))
    }

    @Test
    fun `push-off is the dip plus the takeoff height above standing`() {
        // Dip 30 cm below standing; the top of the flight 50 cm above standing,
        // 40 cm above takeoff, so takeoff is 10 cm above standing: 30 + 10 cm.
        val excursion = JumpExcursion(countermovementCm = 30f, peakRiseCm = 50f, standingYCm = 100f, flightRiseCm = 40f)

        assertEquals(40f, JumpMechanics.pushOffCm(excursion)!!, 0.001f)
    }

    @Test
    fun `push-off needs the rise from takeoff`() {
        val excursion = JumpExcursion(countermovementCm = 30f, peakRiseCm = 50f, standingYCm = 100f, flightRiseCm = null)

        assertNull(JumpMechanics.pushOffCm(excursion))
    }
}
