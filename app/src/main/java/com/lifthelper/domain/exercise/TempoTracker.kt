package com.lifthelper.domain.exercise

import com.lifthelper.domain.model.TempoPrescription
import com.lifthelper.vision.tracking.MovementPhase

/**
 * Immutable state for tempo tracking within a single rep.
 */
data class TempoState(
    val prescription: TempoPrescription,
    val currentPhase: MovementPhase = MovementPhase.IDLE,
    val phaseStartTime: Long = -1L,
    val eccentricActual: Float = 0f,
    val pauseBottomActual: Float = 0f,
    val concentricActual: Float = 0f,
    val pauseTopActual: Float = 0f
) {
    /** Elapsed time in current phase (seconds). */
    fun elapsedInPhase(now: Long): Float =
        if (phaseStartTime >= 0) (now - phaseStartTime) / 1000f else 0f

    /** Prescribed duration for current phase. */
    fun prescribedForCurrentPhase(): Float = when (currentPhase) {
        MovementPhase.ECCENTRIC -> prescription.eccentricSeconds
        MovementPhase.BOTTOM -> prescription.pauseBottomSeconds
        MovementPhase.CONCENTRIC -> prescription.concentricSeconds
        MovementPhase.TOP -> prescription.pauseTopSeconds
        MovementPhase.IDLE -> 0f
    }

    /** Remaining time in current phase. Negative = over time. */
    fun remainingInPhase(now: Long): Float =
        prescribedForCurrentPhase() - elapsedInPhase(now)
}

/**
 * Stateless tempo tracking logic. All functions are pure.
 */
object TempoTracker {

    /**
     * Called when a phase transition occurs. Finalizes the previous phase
     * duration and starts tracking the new phase.
     */
    fun onPhaseTransition(
        state: TempoState,
        newPhase: MovementPhase,
        timestamp: Long
    ): TempoState {
        val elapsed = state.elapsedInPhase(timestamp)

        val updated = when (state.currentPhase) {
            MovementPhase.ECCENTRIC -> state.copy(eccentricActual = elapsed)
            MovementPhase.BOTTOM -> state.copy(pauseBottomActual = elapsed)
            MovementPhase.CONCENTRIC -> state.copy(concentricActual = elapsed)
            MovementPhase.TOP -> state.copy(pauseTopActual = elapsed)
            MovementPhase.IDLE -> state
        }

        return updated.copy(
            currentPhase = newPhase,
            phaseStartTime = timestamp
        )
    }

    /**
     * Determines what feedback cue to show, given the current tempo state.
     */
    fun getCue(state: TempoState, now: Long): TempoCue {
        val elapsed = state.elapsedInPhase(now)
        val prescribed = state.prescribedForCurrentPhase()

        if (prescribed == 0f && state.currentPhase == MovementPhase.CONCENTRIC) {
            return TempoCue.Explosive
        }

        if (prescribed == 0f) return TempoCue.None

        val remaining = prescribed - elapsed
        val ratio = if (prescribed > 0f) elapsed / prescribed else 1f

        return when {
            remaining > 0 -> TempoCue.Countdown(remaining, state.currentPhase)
            ratio > 1.3f -> TempoCue.TooSlow(state.currentPhase)
            ratio < 0.7f -> TempoCue.TooFast(state.currentPhase)
            else -> TempoCue.OnPace(state.currentPhase)
        }
    }

    /**
     * Calculates tempo adherence scores for a completed rep.
     * Returns a score 0.0-1.0 for each phase.
     */
    fun calculateAdherence(state: TempoState): TempoAdherence {
        fun phaseScore(actual: Float, prescribed: Float): Float {
            if (prescribed == 0f) return 1f
            val deviation = kotlin.math.abs(actual - prescribed) / prescribed
            return (1f - deviation).coerceIn(0f, 1f)
        }

        return TempoAdherence(
            eccentricScore = phaseScore(state.eccentricActual, state.prescription.eccentricSeconds),
            pauseBottomScore = phaseScore(state.pauseBottomActual, state.prescription.pauseBottomSeconds),
            concentricScore = if (state.prescription.isExplosiveConcentric) 1f
                else phaseScore(state.concentricActual, state.prescription.concentricSeconds),
            pauseTopScore = phaseScore(state.pauseTopActual, state.prescription.pauseTopSeconds)
        )
    }

    fun reset(prescription: TempoPrescription): TempoState =
        TempoState(prescription = prescription)
}

sealed class TempoCue {
    data class Countdown(val remainingSeconds: Float, val phase: MovementPhase) : TempoCue()
    data class TooFast(val phase: MovementPhase) : TempoCue()
    data class TooSlow(val phase: MovementPhase) : TempoCue()
    data class OnPace(val phase: MovementPhase) : TempoCue()
    data object Explosive : TempoCue()
    data object None : TempoCue()
}

data class TempoAdherence(
    val eccentricScore: Float,
    val pauseBottomScore: Float,
    val concentricScore: Float,
    val pauseTopScore: Float
) {
    /** Weighted average: 40% eccentric, 20% pause, 40% concentric. */
    val overallScore: Float get() =
        eccentricScore * 0.4f + pauseBottomScore * 0.2f + concentricScore * 0.4f
}
