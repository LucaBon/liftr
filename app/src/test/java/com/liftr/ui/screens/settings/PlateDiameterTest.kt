package com.liftr.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateDiameterTest {
    @Test fun `meters to cm rounds float noise`() {
        assertEquals(45, PlateDiameter.metersToCm(0.45f))
        assertEquals(35, PlateDiameter.metersToCm(0.35f))
        assertEquals(28, PlateDiameter.metersToCm(0.28f))
    }

    @Test fun `cm to meters`() {
        assertEquals(0.45f, PlateDiameter.cmToMeters(45), 1e-6f)
    }

    @Test fun `parse accepts range bounds`() {
        assertEquals(0.20f, PlateDiameter.parseCmToMeters("20")!!, 1e-6f)
        assertEquals(0.50f, PlateDiameter.parseCmToMeters(" 50 ")!!, 1e-6f)
        assertEquals(0.28f, PlateDiameter.parseCmToMeters("28")!!, 1e-6f)
    }

    @Test fun `parse rejects out of range and garbage`() {
        assertNull(PlateDiameter.parseCmToMeters("19"))
        assertNull(PlateDiameter.parseCmToMeters("51"))
        assertNull(PlateDiameter.parseCmToMeters(""))
        assertNull(PlateDiameter.parseCmToMeters("abc"))
        assertNull(PlateDiameter.parseCmToMeters("-30"))
    }

    @Test fun `preset detection`() {
        assertTrue(PlateDiameter.isPreset(0.45f))
        assertTrue(PlateDiameter.isPreset(0.30f))
        assertFalse(PlateDiameter.isPreset(0.28f))
    }
}
