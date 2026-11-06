package com.liftr.data.local.database

import com.liftr.domain.calculator.JumpMechanics
import com.liftr.domain.model.RepJumpData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepJumpDataConverterTest {

    private val converters = Converters()

    @Test
    fun `a row saved before peak power still decodes, without power`() {
        val json = """{"flightHeightCm":31.5,"apexHeightCm":30.2,"takeoffVelocity":2.48,"path":[]}"""

        val jump = converters.toRepJumpData(json)!!

        assertEquals(31.5f, jump.flightHeightCm, 0.001f)
        assertNull(jump.peakPowerW)
        assertNull(jump.pushOffCm)
        assertNull(jump.meanForceN)
    }

    @Test
    fun `a row saved before push-off mechanics still decodes, without them`() {
        val json = """{"flightHeightCm":31.5,"peakPowerW":3254.0,"path":[]}"""

        val jump = converters.toRepJumpData(json)!!

        assertEquals(3254f, jump.peakPowerW!!, 0.001f)
        assertNull(jump.pushOffCm)
        assertNull(jump.meanForceN)
        assertNull(jump.meanVelocity)
        assertNull(jump.meanPowerW)
        assertNull(jump.systemMassKg)
        assertNull(jump.relativeForce)
        assertNull(jump.relativePower)
    }

    @Test
    fun `push-off mechanics round-trip`() {
        val mechanics = JumpMechanics.of(jumpHeightM = 0.40f, pushOffM = 0.40f, systemMassKg = 75f)
        val jump = RepJumpData.from(flightHeightCm = 40f, apexHeightCm = null, barPath = null, peakPowerW = 3305f, mechanics = mechanics)

        val decoded = converters.toRepJumpData(converters.fromRepJumpData(jump))!!

        assertEquals(jump, decoded)
        assertEquals(40f, decoded.pushOffCm!!, 0.001f)
        assertEquals(1471.5f, decoded.meanForceN!!, 0.01f)
        assertEquals(1.4007f, decoded.meanVelocity!!, 0.0001f)
        assertEquals(2061.1f, decoded.meanPowerW!!, 0.2f)
        assertEquals(19.62f, decoded.relativeForce!!, 0.001f)
    }

    @Test
    fun `peak power round-trips`() {
        val jump = RepJumpData(flightHeightCm = 31.5f, peakPowerW = 3254f)

        val decoded = converters.toRepJumpData(converters.fromRepJumpData(jump))

        assertEquals(jump, decoded)
    }
}
