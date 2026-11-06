package com.liftr.data.local.database

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
    }

    @Test
    fun `peak power round-trips`() {
        val jump = RepJumpData(flightHeightCm = 31.5f, peakPowerW = 3254f)

        val decoded = converters.toRepJumpData(converters.fromRepJumpData(jump))

        assertEquals(jump, decoded)
    }

    @Test
    fun `a row saved before contact time still decodes, without contact time or RSI`() {
        val json = """{"flightHeightCm":31.5,"peakPowerW":3254.0,"path":[]}"""

        val jump = converters.toRepJumpData(json)!!

        assertEquals(3254f, jump.peakPowerW!!, 0.001f)
        assertNull(jump.contactTimeMs)
        assertNull(jump.reactiveStrengthIndex)
        assertNull(jump.rsiHeightMps)
    }

    @Test
    fun `contact time and RSI round-trip`() {
        val jump = RepJumpData(flightHeightCm = 20f, contactTimeMs = 200f, reactiveStrengthIndex = 2.0f)

        val decoded = converters.toRepJumpData(converters.fromRepJumpData(jump))!!

        assertEquals(jump, decoded)
        assertEquals(1.0f, decoded.rsiHeightMps!!, 1e-5f)
    }
}
