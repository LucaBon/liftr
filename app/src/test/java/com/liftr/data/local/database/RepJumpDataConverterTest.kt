package com.liftr.data.local.database

import com.liftr.domain.model.JumpFitCheck
import com.liftr.domain.model.JumpQuality
import com.liftr.domain.model.RepJumpData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `quality is derived from the stored fit check, not saved`() {
        val failed = JumpFitCheck(6f, 8f, false, 2f, 0f, false, true, 0.5f, 20)
        val jump = RepJumpData(flightHeightCm = 31.5f, fitCheck = failed)

        val json = converters.fromRepJumpData(jump)!!
        val decoded = converters.toRepJumpData(json)!!

        assertEquals(JumpQuality.SUSPECT, decoded.quality)
        assertFalse(json.contains("quality") || json.contains("isSuspect"))
    }

    @Test
    fun `a row saved without a fit check is unchecked`() {
        val jump = converters.toRepJumpData("""{"flightHeightCm":31.5,"path":[]}""")!!

        assertEquals(JumpQuality.UNCHECKED, jump.quality)
    }
}
