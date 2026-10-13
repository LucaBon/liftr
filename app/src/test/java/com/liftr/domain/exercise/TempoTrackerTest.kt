package com.liftr.domain.exercise

import com.liftr.domain.model.TempoPrescription
import com.liftr.domain.model.MovementPhase
import org.junit.Assert.*
import org.junit.Test

class TempoTrackerTest {

    // Construct directly to avoid JaCoCo companion object instrumentation issues
    private val hypertrophy = TempoPrescription(3f, 1f, 2f, 0f)

    @Test
    fun `reset produces clean state with given prescription`() {
        val state = TempoTracker.reset(hypertrophy)
        assertEquals(MovementPhase.IDLE, state.currentPhase)
        assertEquals(0f, state.eccentricActual, 0.01f)
        assertEquals(0f, state.pauseBottomActual, 0.01f)
        assertEquals(0f, state.concentricActual, 0.01f)
        assertEquals(0f, state.pauseTopActual, 0.01f)
        assertEquals(-1L, state.phaseStartTime)
    }

    @Test
    fun `onPhaseTransition records eccentric duration correctly`() {
        var state = TempoTracker.reset(hypertrophy)

        // Start eccentric at t=1000
        state = TempoTracker.onPhaseTransition(state, MovementPhase.ECCENTRIC, 1000L)
        assertEquals(MovementPhase.ECCENTRIC, state.currentPhase)
        assertEquals(1000L, state.phaseStartTime)

        // Transition to bottom at t=4000 (3 seconds eccentric)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.BOTTOM, 4000L)
        assertEquals(3f, state.eccentricActual, 0.01f)
        assertEquals(MovementPhase.BOTTOM, state.currentPhase)
    }

    @Test
    fun `onPhaseTransition records all phases in sequence`() {
        var state = TempoTracker.reset(hypertrophy)

        // Eccentric: 3 seconds
        state = TempoTracker.onPhaseTransition(state, MovementPhase.ECCENTRIC, 1000L)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.BOTTOM, 4000L)
        assertEquals(3f, state.eccentricActual, 0.01f)

        // Bottom pause: 1 second
        state = TempoTracker.onPhaseTransition(state, MovementPhase.CONCENTRIC, 5000L)
        assertEquals(1f, state.pauseBottomActual, 0.01f)

        // Concentric: 2 seconds
        state = TempoTracker.onPhaseTransition(state, MovementPhase.TOP, 7000L)
        assertEquals(2f, state.concentricActual, 0.01f)

        // Top pause: 0.5 seconds
        state = TempoTracker.onPhaseTransition(state, MovementPhase.IDLE, 7500L)
        assertEquals(0.5f, state.pauseTopActual, 0.01f)
    }

    @Test
    fun `elapsedInPhase returns correct duration`() {
        var state = TempoTracker.reset(hypertrophy)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.ECCENTRIC, 1000L)

        assertEquals(2.5f, state.elapsedInPhase(3500L), 0.01f)
    }

    @Test
    fun `remainingInPhase returns correct remaining time`() {
        var state = TempoTracker.reset(hypertrophy)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.ECCENTRIC, 1000L)

        // Prescribed eccentric is 3s, elapsed is 1s => remaining = 2s
        assertEquals(2f, state.remainingInPhase(2000L), 0.01f)
    }

    @Test
    fun `remainingInPhase returns negative when over time`() {
        var state = TempoTracker.reset(hypertrophy)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.ECCENTRIC, 1000L)

        // Prescribed eccentric is 3s, elapsed is 4s => remaining = -1s
        assertEquals(-1f, state.remainingInPhase(5000L), 0.01f)
    }

    @Test
    fun `prescribedForCurrentPhase returns correct value per phase`() {
        var state = TempoTracker.reset(hypertrophy)

        state = state.copy(currentPhase = MovementPhase.ECCENTRIC)
        assertEquals(3f, state.prescribedForCurrentPhase(), 0.01f)

        state = state.copy(currentPhase = MovementPhase.BOTTOM)
        assertEquals(1f, state.prescribedForCurrentPhase(), 0.01f)

        state = state.copy(currentPhase = MovementPhase.CONCENTRIC)
        assertEquals(2f, state.prescribedForCurrentPhase(), 0.01f)

        state = state.copy(currentPhase = MovementPhase.TOP)
        assertEquals(0f, state.prescribedForCurrentPhase(), 0.01f)

        state = state.copy(currentPhase = MovementPhase.IDLE)
        assertEquals(0f, state.prescribedForCurrentPhase(), 0.01f)
    }

    // ── getCue tests ──

    @Test
    fun `getCue returns Countdown when time remains`() {
        var state = TempoTracker.reset(hypertrophy)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.ECCENTRIC, 1000L)

        val cue = TempoTracker.getCue(state, 2000L) // 1s elapsed, 2s remaining
        assertTrue(cue is TempoCue.Countdown)
        val countdown = cue as TempoCue.Countdown
        assertEquals(2f, countdown.remainingSeconds, 0.1f)
        assertEquals(MovementPhase.ECCENTRIC, countdown.phase)
    }

    @Test
    fun `getCue returns TooSlow when over 130 percent of prescribed time`() {
        var state = TempoTracker.reset(hypertrophy)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.ECCENTRIC, 1000L)

        // 3s prescribed, 4.5s elapsed = 150% = too slow
        val cue = TempoTracker.getCue(state, 5500L)
        assertTrue(cue is TempoCue.TooSlow)
    }

    @Test
    fun `getCue returns OnPace when within acceptable range`() {
        var state = TempoTracker.reset(hypertrophy)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.ECCENTRIC, 1000L)

        // 3s prescribed, 3.2s elapsed = 107% = on pace
        val cue = TempoTracker.getCue(state, 4200L)
        assertTrue(cue is TempoCue.OnPace)
    }

    @Test
    fun `getCue returns Explosive for zero concentric`() {
        val explosive = TempoPrescription(1f, 0f, 0f, 0f)
        var state = TempoTracker.reset(explosive)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.CONCENTRIC, 1000L)

        val cue = TempoTracker.getCue(state, 1500L)
        assertTrue(cue is TempoCue.Explosive)
    }

    @Test
    fun `getCue returns None for zero prescribed non-concentric phase`() {
        // TOP phase with 0s prescribed
        var state = TempoTracker.reset(hypertrophy)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.TOP, 1000L)

        val cue = TempoTracker.getCue(state, 1500L)
        assertTrue(cue is TempoCue.None)
    }

    // ── calculateAdherence tests ──

    @Test
    fun `perfect adherence returns 1_0 overall`() {
        var state = TempoTracker.reset(hypertrophy)

        // Simulate perfect 3-1-2-0 rep (start at t=1000 to avoid phaseStartTime==0 edge case)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.ECCENTRIC, 1000L)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.BOTTOM, 4000L)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.CONCENTRIC, 5000L)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.IDLE, 7000L)

        val adherence = TempoTracker.calculateAdherence(state)
        assertEquals(1f, adherence.eccentricScore, 0.01f)
        assertEquals(1f, adherence.pauseBottomScore, 0.01f)
        assertEquals(1f, adherence.concentricScore, 0.01f)
        assertEquals(1f, adherence.overallScore, 0.01f)
    }

    @Test
    fun `50 percent deviation returns 0_5 phase score`() {
        var state = TempoTracker.reset(hypertrophy)

        // Eccentric: prescribed 3s, actual 1.5s = 50% deviation
        state = TempoTracker.onPhaseTransition(state, MovementPhase.ECCENTRIC, 1000L)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.BOTTOM, 2500L)

        val adherence = TempoTracker.calculateAdherence(state)
        assertEquals(0.5f, adherence.eccentricScore, 0.01f)
    }

    @Test
    fun `explosive concentric always scores 1_0`() {
        val explosive = TempoPrescription(1f, 0f, 0f, 0f)
        var state = TempoTracker.reset(explosive)

        state = TempoTracker.onPhaseTransition(state, MovementPhase.ECCENTRIC, 1000L)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.CONCENTRIC, 2000L)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.IDLE, 2200L)

        val adherence = TempoTracker.calculateAdherence(state)
        assertEquals(1f, adherence.concentricScore, 0.01f)
    }

    @Test
    fun `zero prescribed phase scores 1_0`() {
        // pauseTop = 0 in hypertrophy preset
        var state = TempoTracker.reset(hypertrophy)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.TOP, 1000L)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.IDLE, 1500L)

        val adherence = TempoTracker.calculateAdherence(state)
        assertEquals(1f, adherence.pauseTopScore, 0.01f)
    }

    @Test
    fun `overallScore uses correct weights`() {
        val adherence = TempoAdherence(
            eccentricScore = 1f,
            pauseBottomScore = 0f,
            concentricScore = 1f,
            pauseTopScore = 0f // not weighted in overall
        )
        // 1.0 * 0.4 + 0.0 * 0.2 + 1.0 * 0.4 = 0.8
        assertEquals(0.8f, adherence.overallScore, 0.01f)
    }

    @Test
    fun `deviation beyond 100 percent clamps to 0`() {
        var state = TempoTracker.reset(hypertrophy)

        // Eccentric: prescribed 3s, actual 0s = 100% deviation
        state = TempoTracker.onPhaseTransition(state, MovementPhase.ECCENTRIC, 1000L)
        state = TempoTracker.onPhaseTransition(state, MovementPhase.BOTTOM, 1000L)

        val adherence = TempoTracker.calculateAdherence(state)
        assertEquals(0f, adherence.eccentricScore, 0.01f)
    }
}
