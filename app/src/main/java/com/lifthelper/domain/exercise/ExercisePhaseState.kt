package com.lifthelper.domain.exercise

import com.lifthelper.vision.tracking.MovementPhase

/**
 * Immutable state object for exercise phase detection.
 *
 * This class holds all the state information needed for phase transition detection
 * in exercise strategies. By making this immutable and passing it as a parameter,
 * we ensure thread safety and make the strategy classes stateless.
 *
 * @property currentPhase The current movement phase
 * @property startingHeadY Starting head Y position in meters (null if not initialized)
 * @property startingShouldersY Starting shoulders Y position in meters (null if not initialized)
 * @property startingHipsY Starting hips Y position in meters (null if not initialized)
 * @property lowestBarPosition Lowest bar position reached during descent (null if not tracked)
 * @property currentHeadY Current head Y position in meters (null if not detected)
 * @property currentShouldersY Current shoulders Y position in meters (null if not detected)
 * @property currentHipsY Current hips Y position in meters (null if not detected)
 * @property currentKneesY Current knees Y position in meters (null if not detected)
 */
data class ExercisePhaseState(
    val currentPhase: MovementPhase = MovementPhase.IDLE,
    val startingHeadY: Float? = null,
    val startingShouldersY: Float? = null,
    val startingHipsY: Float? = null,
    val lowestBarPosition: Float? = null,
    val currentHeadY: Float? = null,
    val currentShouldersY: Float? = null,
    val currentHipsY: Float? = null,
    val currentKneesY: Float? = null
) {
    /**
     * Create a new state with updated current phase.
     */
    fun withCurrentPhase(phase: MovementPhase): ExercisePhaseState =
        copy(currentPhase = phase)

    /**
     * Create a new state with updated starting positions.
     */
    fun withStartingPosition(
        headY: Float?,
        shouldersY: Float?,
        hipsY: Float?
    ): ExercisePhaseState =
        copy(
            startingHeadY = headY,
            startingShouldersY = shouldersY,
            startingHipsY = hipsY
        )

    /**
     * Create a new state with updated current positions.
     */
    fun withCurrentPositions(
        headY: Float?,
        shouldersY: Float?,
        hipsY: Float?,
        kneesY: Float?
    ): ExercisePhaseState =
        copy(
            currentHeadY = headY,
            currentShouldersY = shouldersY,
            currentHipsY = hipsY,
            currentKneesY = kneesY
        )

    /**
     * Create a new state with updated lowest bar position.
     */
    fun withLowestBarPosition(position: Float?): ExercisePhaseState =
        copy(lowestBarPosition = position)

    companion object {
        /**
         * Creates a fresh initial state for a new workout/exercise.
         */
        fun initial(): ExercisePhaseState = ExercisePhaseState()
    }
}
