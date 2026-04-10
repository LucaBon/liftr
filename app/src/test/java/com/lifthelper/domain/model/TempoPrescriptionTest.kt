package com.lifthelper.domain.model

import org.junit.Assert.*
import org.junit.Test

class TempoPrescriptionTest {

    @Test
    fun `fromString parses standard notation correctly`() {
        val result = TempoPrescription.fromString("3-1-2-0")
        assertNotNull(result)
        assertEquals(3f, result!!.eccentricSeconds, 0.01f)
        assertEquals(1f, result.pauseBottomSeconds, 0.01f)
        assertEquals(2f, result.concentricSeconds, 0.01f)
        assertEquals(0f, result.pauseTopSeconds, 0.01f)
    }

    @Test
    fun `fromString handles explosive X notation`() {
        val result = TempoPrescription.fromString("3-1-X-0")
        assertNotNull(result)
        assertEquals(0f, result!!.concentricSeconds, 0.01f)
        assertTrue(result.isExplosiveConcentric)
    }

    @Test
    fun `fromString handles lowercase x notation`() {
        val result = TempoPrescription.fromString("2-0-x-0")
        assertNotNull(result)
        assertTrue(result!!.isExplosiveConcentric)
    }

    @Test
    fun `fromString returns null for invalid format`() {
        assertNull(TempoPrescription.fromString("3-1-2"))
        assertNull(TempoPrescription.fromString(""))
        assertNull(TempoPrescription.fromString("a-b-c-d"))
        assertNull(TempoPrescription.fromString("3-1-2-0-1"))
    }

    @Test
    fun `isExplosiveConcentric is false when concentric is prescribed`() {
        val prescription = TempoPrescription(3f, 1f, 2f, 0f)
        assertFalse(prescription.isExplosiveConcentric)
    }

    @Test
    fun `isExplosiveConcentric is true when concentric is zero`() {
        val prescription = TempoPrescription(1f, 0f, 0f, 0f)
        assertTrue(prescription.isExplosiveConcentric)
    }

    @Test
    fun `totalDuration sums all phases`() {
        val prescription = TempoPrescription(3f, 1f, 2f, 1f)
        assertEquals(7f, prescription.totalDuration, 0.01f)
    }

    @Test
    fun `HYPERTROPHY preset has correct values`() {
        val p = TempoPrescription.HYPERTROPHY
        assertEquals(3f, p.eccentricSeconds, 0.01f)
        assertEquals(1f, p.pauseBottomSeconds, 0.01f)
        assertEquals(2f, p.concentricSeconds, 0.01f)
        assertEquals(0f, p.pauseTopSeconds, 0.01f)
    }

    @Test
    fun `STRENGTH preset has correct values`() {
        val p = TempoPrescription.STRENGTH
        assertEquals(2f, p.eccentricSeconds, 0.01f)
        assertEquals(0f, p.pauseBottomSeconds, 0.01f)
        assertEquals(1f, p.concentricSeconds, 0.01f)
        assertEquals(0f, p.pauseTopSeconds, 0.01f)
    }

    @Test
    fun `SLOW preset has correct values`() {
        val p = TempoPrescription.SLOW
        assertEquals(4f, p.eccentricSeconds, 0.01f)
        assertEquals(2f, p.pauseBottomSeconds, 0.01f)
        assertEquals(4f, p.concentricSeconds, 0.01f)
        assertEquals(0f, p.pauseTopSeconds, 0.01f)
    }

    @Test
    fun `EXPLOSIVE preset is explosive concentric`() {
        assertTrue(TempoPrescription.EXPLOSIVE.isExplosiveConcentric)
    }

    @Test
    fun `ISOMETRIC preset has long pause`() {
        assertEquals(5f, TempoPrescription.ISOMETRIC.pauseBottomSeconds, 0.01f)
    }

    @Test
    fun `toDisplayString formats correctly`() {
        val p = TempoPrescription(3f, 1f, 2f, 0f)
        val display = p.toDisplayString()
        assertTrue(display.contains("3"))
        assertTrue(display.contains("1"))
    }

    @Test
    fun `toDisplayString shows X for explosive`() {
        val p = TempoPrescription.EXPLOSIVE
        assertTrue(p.toDisplayString().contains("X"))
    }
}
