package com.liftr.domain.exercise

import com.liftr.domain.value.MetersPerSecond
import org.junit.Assert.*
import org.junit.Test

class VelocityLossTrackerTest {

    @Test
    fun `reset should create fresh state with given threshold`() {
        val state = VelocityLossTracker.reset(0.15f)

        assertEquals(MetersPerSecond(0f), state.fastestVelocity)
        assertEquals(0f, state.currentLossPercent, 0.001f)
        assertEquals(0.15f, state.threshold, 0.001f)
        assertFalse(state.shouldStop)
        assertTrue(state.repVelocities.isEmpty())
    }

    @Test
    fun `reset with default threshold should use 20 percent`() {
        val state = VelocityLossTracker.reset()
        assertEquals(VelocityLossState.DEFAULT_THRESHOLD, state.threshold, 0.001f)
    }

    @Test
    fun `first rep should set fastest velocity and zero loss`() {
        val state = VelocityLossTracker.reset()
        val updated = VelocityLossTracker.update(state, MetersPerSecond(0.80f))

        assertEquals(0.80f, updated.fastestVelocity.value, 0.001f)
        assertEquals(0f, updated.currentLossPercent, 0.001f)
        assertFalse(updated.shouldStop)
        assertEquals(1, updated.repVelocities.size)
    }

    @Test
    fun `second rep slower should calculate loss correctly`() {
        var state = VelocityLossTracker.reset()
        state = VelocityLossTracker.update(state, MetersPerSecond(1.00f))
        state = VelocityLossTracker.update(state, MetersPerSecond(0.85f))

        assertEquals(1.00f, state.fastestVelocity.value, 0.001f)
        assertEquals(0.15f, state.currentLossPercent, 0.001f)
        assertFalse(state.shouldStop) // 15% < 20% threshold
        assertEquals(2, state.repVelocities.size)
    }

    @Test
    fun `should trigger stop when loss exceeds threshold`() {
        var state = VelocityLossTracker.reset(0.20f)
        state = VelocityLossTracker.update(state, MetersPerSecond(1.00f))
        state = VelocityLossTracker.update(state, MetersPerSecond(0.75f))

        assertEquals(0.25f, state.currentLossPercent, 0.001f)
        assertTrue(state.shouldStop) // 25% > 20% threshold
    }

    @Test
    fun `should trigger stop when clearly over threshold`() {
        var state = VelocityLossTracker.reset(0.20f)
        state = VelocityLossTracker.update(state, MetersPerSecond(1.00f))
        // Use 0.79 to clearly exceed 20% threshold (21% loss), avoiding float precision edge
        state = VelocityLossTracker.update(state, MetersPerSecond(0.79f))

        assertTrue(state.currentLossPercent > 0.20f)
        assertTrue(state.shouldStop)
    }

    @Test
    fun `should not trigger stop on first rep even with zero velocity`() {
        var state = VelocityLossTracker.reset(0.10f)
        state = VelocityLossTracker.update(state, MetersPerSecond(0.50f))

        assertFalse(state.shouldStop)
        assertEquals(1, state.repVelocities.size)
    }

    @Test
    fun `should not trigger stop when disabled`() {
        var state = VelocityLossTracker.reset(VelocityLossState.DISABLED_THRESHOLD)
        state = VelocityLossTracker.update(state, MetersPerSecond(1.00f))
        state = VelocityLossTracker.update(state, MetersPerSecond(0.10f))

        assertFalse(state.shouldStop)
        assertFalse(state.isEnabled)
    }

    @Test
    fun `should update fastest when new rep is faster`() {
        var state = VelocityLossTracker.reset()
        state = VelocityLossTracker.update(state, MetersPerSecond(0.70f))
        state = VelocityLossTracker.update(state, MetersPerSecond(0.90f))

        assertEquals(0.90f, state.fastestVelocity.value, 0.001f)
        assertEquals(0f, state.currentLossPercent, 0.001f)
    }

    @Test
    fun `should use absolute velocity for negative values`() {
        var state = VelocityLossTracker.reset()
        state = VelocityLossTracker.update(state, MetersPerSecond(-1.00f))
        state = VelocityLossTracker.update(state, MetersPerSecond(-0.80f))

        assertEquals(1.00f, state.fastestVelocity.value, 0.001f)
        assertEquals(0.20f, state.currentLossPercent, 0.001f)
    }

    @Test
    fun `should accumulate rep velocities in order`() {
        var state = VelocityLossTracker.reset()
        state = VelocityLossTracker.update(state, MetersPerSecond(1.00f))
        state = VelocityLossTracker.update(state, MetersPerSecond(0.95f))
        state = VelocityLossTracker.update(state, MetersPerSecond(0.88f))
        state = VelocityLossTracker.update(state, MetersPerSecond(0.80f))

        assertEquals(4, state.repVelocities.size)
        assertEquals(1.00f, state.repVelocities[0].value, 0.001f)
        assertEquals(0.95f, state.repVelocities[1].value, 0.001f)
        assertEquals(0.88f, state.repVelocities[2].value, 0.001f)
        assertEquals(0.80f, state.repVelocities[3].value, 0.001f)
    }

    @Test
    fun `repCount should reflect number of velocities tracked`() {
        var state = VelocityLossTracker.reset()
        assertEquals(0, state.repCount)

        state = VelocityLossTracker.update(state, MetersPerSecond(1.00f))
        assertEquals(1, state.repCount)

        state = VelocityLossTracker.update(state, MetersPerSecond(0.90f))
        assertEquals(2, state.repCount)
    }

    @Test
    fun `should handle zero fastest velocity gracefully`() {
        var state = VelocityLossTracker.reset()
        state = VelocityLossTracker.update(state, MetersPerSecond(0.00f))

        assertEquals(0f, state.currentLossPercent, 0.001f)
        assertFalse(state.shouldStop)
    }

    @Test
    fun `realistic set scenario with progressive fatigue`() {
        var state = VelocityLossTracker.reset(0.20f)

        // Rep 1: baseline
        state = VelocityLossTracker.update(state, MetersPerSecond(0.80f))
        assertFalse(state.shouldStop)

        // Rep 2: slight slowdown
        state = VelocityLossTracker.update(state, MetersPerSecond(0.76f))
        assertEquals(0.05f, state.currentLossPercent, 0.01f)
        assertFalse(state.shouldStop)

        // Rep 3: more fatigue
        state = VelocityLossTracker.update(state, MetersPerSecond(0.72f))
        assertEquals(0.10f, state.currentLossPercent, 0.01f)
        assertFalse(state.shouldStop)

        // Rep 4: significant drop
        state = VelocityLossTracker.update(state, MetersPerSecond(0.68f))
        assertEquals(0.15f, state.currentLossPercent, 0.01f)
        assertFalse(state.shouldStop)

        // Rep 5: threshold exceeded
        state = VelocityLossTracker.update(state, MetersPerSecond(0.62f))
        assertEquals(0.225f, state.currentLossPercent, 0.01f)
        assertTrue(state.shouldStop)
    }
}
