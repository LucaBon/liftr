package com.liftr.domain.exercise

import com.liftr.domain.value.MetersPerSecond

/**
 * Immutable state representing velocity loss tracking within a set.
 *
 * Tracks the fastest rep velocity and calculates velocity loss percentage
 * to determine when a lifter should stop the set (auto-stop).
 *
 * Based on Sanchez-Medina & Gonzalez-Badillo (2011): velocity loss
 * correlates with neuromuscular fatigue and training stimulus.
 */
data class VelocityLossState(
    val fastestVelocity: MetersPerSecond = MetersPerSecond(0f),
    val currentLossPercent: Float = 0f,
    val threshold: Float = DEFAULT_THRESHOLD,
    val shouldStop: Boolean = false,
    val repVelocities: List<MetersPerSecond> = emptyList()
) {
    companion object {
        const val DEFAULT_THRESHOLD = 0.20f
        const val DISABLED_THRESHOLD = -1f
    }

    val isEnabled: Boolean get() = threshold != DISABLED_THRESHOLD
    val repCount: Int get() = repVelocities.size
}

/**
 * Pure-function velocity loss tracker. No mutable state — produces new
 * [VelocityLossState] instances on each update.
 *
 * Velocity loss is only meaningful from rep 2 onwards, since the first
 * rep establishes the baseline.
 */
object VelocityLossTracker {

    /**
     * Updates the velocity loss state with a new rep's mean concentric velocity.
     *
     * @param state Current immutable state
     * @param newRepVelocity Mean concentric velocity of the completed rep (absolute value used)
     * @return New state with updated loss calculation
     */
    fun update(
        state: VelocityLossState,
        newRepVelocity: MetersPerSecond
    ): VelocityLossState {
        val absVelocity = newRepVelocity.abs()
        val fastest = if (absVelocity.value > state.fastestVelocity.value) {
            absVelocity
        } else {
            state.fastestVelocity
        }

        val loss = if (fastest.value > 0f) {
            1f - (absVelocity.value / fastest.value)
        } else {
            0f
        }

        val updatedVelocities = state.repVelocities + absVelocity

        // Only flag shouldStop from rep 2 onwards and when tracking is enabled
        val shouldStop = state.isEnabled &&
            updatedVelocities.size >= 2 &&
            loss >= state.threshold

        return state.copy(
            fastestVelocity = fastest,
            currentLossPercent = loss,
            shouldStop = shouldStop,
            repVelocities = updatedVelocities
        )
    }

    /**
     * Creates a fresh state for a new set.
     *
     * @param threshold Velocity loss threshold (0.10-0.30), or [VelocityLossState.DISABLED_THRESHOLD] to disable
     */
    fun reset(threshold: Float = VelocityLossState.DEFAULT_THRESHOLD): VelocityLossState {
        return VelocityLossState(threshold = threshold)
    }
}
