package com.liftr.domain.model

import com.liftr.domain.value.MetersPerSecond
import org.junit.Assert.assertEquals
import org.junit.Test

class VbtZoneTest {

    @Test
    fun `should classify grinding velocity correctly`() {
        assertEquals(VbtZone.GRINDING, VbtZone.fromVelocity(MetersPerSecond(0.15f)))
        assertEquals(VbtZone.GRINDING, VbtZone.fromVelocity(MetersPerSecond(0.00f)))
        assertEquals(VbtZone.GRINDING, VbtZone.fromVelocity(MetersPerSecond(0.29f)))
    }

    @Test
    fun `should classify max strength velocity correctly`() {
        assertEquals(VbtZone.MAX_STRENGTH, VbtZone.fromVelocity(MetersPerSecond(0.30f)))
        assertEquals(VbtZone.MAX_STRENGTH, VbtZone.fromVelocity(MetersPerSecond(0.40f)))
        assertEquals(VbtZone.MAX_STRENGTH, VbtZone.fromVelocity(MetersPerSecond(0.49f)))
    }

    @Test
    fun `should classify strength-speed velocity correctly`() {
        assertEquals(VbtZone.STRENGTH_SPEED, VbtZone.fromVelocity(MetersPerSecond(0.50f)))
        assertEquals(VbtZone.STRENGTH_SPEED, VbtZone.fromVelocity(MetersPerSecond(0.60f)))
        assertEquals(VbtZone.STRENGTH_SPEED, VbtZone.fromVelocity(MetersPerSecond(0.74f)))
    }

    @Test
    fun `should classify speed-strength velocity correctly`() {
        assertEquals(VbtZone.SPEED_STRENGTH, VbtZone.fromVelocity(MetersPerSecond(0.75f)))
        assertEquals(VbtZone.SPEED_STRENGTH, VbtZone.fromVelocity(MetersPerSecond(0.85f)))
        assertEquals(VbtZone.SPEED_STRENGTH, VbtZone.fromVelocity(MetersPerSecond(0.99f)))
    }

    @Test
    fun `should classify speed-power velocity correctly`() {
        assertEquals(VbtZone.SPEED_POWER, VbtZone.fromVelocity(MetersPerSecond(1.00f)))
        assertEquals(VbtZone.SPEED_POWER, VbtZone.fromVelocity(MetersPerSecond(1.50f)))
        assertEquals(VbtZone.SPEED_POWER, VbtZone.fromVelocity(MetersPerSecond(2.50f)))
    }

    @Test
    fun `should handle negative velocity using absolute value`() {
        assertEquals(VbtZone.STRENGTH_SPEED, VbtZone.fromVelocity(MetersPerSecond(-0.60f)))
        assertEquals(VbtZone.SPEED_POWER, VbtZone.fromVelocity(MetersPerSecond(-1.20f)))
    }

    @Test
    fun `should classify exact boundary values at lower bound of each zone`() {
        assertEquals(VbtZone.GRINDING, VbtZone.fromVelocity(MetersPerSecond(0.00f)))
        assertEquals(VbtZone.MAX_STRENGTH, VbtZone.fromVelocity(MetersPerSecond(0.30f)))
        assertEquals(VbtZone.STRENGTH_SPEED, VbtZone.fromVelocity(MetersPerSecond(0.50f)))
        assertEquals(VbtZone.SPEED_STRENGTH, VbtZone.fromVelocity(MetersPerSecond(0.75f)))
        assertEquals(VbtZone.SPEED_POWER, VbtZone.fromVelocity(MetersPerSecond(1.00f)))
    }

    @Test
    fun `should have correct display names`() {
        assertEquals("Grinding", VbtZone.GRINDING.displayName)
        assertEquals("Max Strength", VbtZone.MAX_STRENGTH.displayName)
        assertEquals("Strength-Speed", VbtZone.STRENGTH_SPEED.displayName)
        assertEquals("Speed-Strength", VbtZone.SPEED_STRENGTH.displayName)
        assertEquals("Speed/Power", VbtZone.SPEED_POWER.displayName)
    }

    @Test
    fun `should have correct color codes`() {
        assertEquals(0xFFE53935, VbtZone.GRINDING.colorHex)
        assertEquals(0xFFFF9800, VbtZone.MAX_STRENGTH.colorHex)
        assertEquals(0xFFFFEB3B, VbtZone.STRENGTH_SPEED.colorHex)
        assertEquals(0xFF4CAF50, VbtZone.SPEED_STRENGTH.colorHex)
        assertEquals(0xFF2196F3, VbtZone.SPEED_POWER.colorHex)
    }

    @Test
    fun `should have contiguous zone boundaries`() {
        val zones = VbtZone.entries
        for (i in 0 until zones.size - 1) {
            assertEquals(
                "Zone ${zones[i].name} maxVelocity should equal ${zones[i + 1].name} minVelocity",
                zones[i].maxVelocity,
                zones[i + 1].minVelocity,
                0.001f
            )
        }
    }

    @Test
    fun `should cover zero to max velocity without gaps`() {
        assertEquals(0.00f, VbtZone.entries.first().minVelocity, 0.001f)
        assertEquals(Float.MAX_VALUE, VbtZone.entries.last().maxVelocity, 0.001f)
    }

    // --- Non-finite / out-of-range inputs -------------------------------
    // The lookup previously used entries.first { ... }, which throws
    // NoSuchElementException for any value matching no range. NaN fails every
    // comparison, and the top zone's upper bound is exclusive so MAX_VALUE and
    // infinity fall through it. All three are reachable from upstream velocity
    // math (division by a zero time delta or a degenerate calibration).

    @Test
    fun `should not throw on NaN velocity`() {
        assertEquals(VbtZone.GRINDING, VbtZone.fromVelocity(MetersPerSecond(Float.NaN)))
    }

    @Test
    fun `should not throw on infinite velocity`() {
        // Infinity comes from dividing by a zero time delta — an unusable
        // reading, not a genuinely fast rep. Classified with NaN as the slowest
        // zone so it can never fabricate an explosive-power personal record.
        assertEquals(
            VbtZone.GRINDING,
            VbtZone.fromVelocity(MetersPerSecond(Float.POSITIVE_INFINITY))
        )
    }

    @Test
    fun `should not throw on max float velocity`() {
        assertEquals(VbtZone.SPEED_POWER, VbtZone.fromVelocity(MetersPerSecond(Float.MAX_VALUE)))
    }
}
