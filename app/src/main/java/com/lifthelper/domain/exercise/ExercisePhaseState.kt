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
 * @property currentHipsX Current hips X position in meters (null if not detected)
 * @property currentKneesY Current knees Y position in meters (null if not detected)
 * @property currentKneesX Current knees X position in meters (null if not detected)
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
    val currentHipsX: Float? = null,
    val currentKneesY: Float? = null,
    val currentKneesX: Float? = null,
    // Bench press fields
    val currentWristsY: Float? = null,
    val currentWristsX: Float? = null,
    val currentElbowsY: Float? = null,
    val currentElbowAngle: Float? = null,
    val startingWristsY: Float? = null,
    val lowestWristsY: Float? = null,
    // Deadlift fields
    val highestHipsY: Float? = null,
    val startingTorsoAngle: Float? = null,
    val currentAnklesY: Float? = null,
    val currentAnklesX: Float? = null,
    val currentShouldersX: Float? = null,
    // Olympic lift fields
    val peakPullVelocity: Float? = null,
    val catchPosition: Float? = null,
    val firstPullStartTime: Long? = null,
    val secondPullStartTime: Long? = null,
    val catchTime: Long? = null,
    val previousWristsY: Float? = null,
    val previousHipsY: Float? = null,
    val previousTimestamp: Long? = null
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
        kneesY: Float?,
        hipsX: Float? = null,
        kneesX: Float? = null
    ): ExercisePhaseState =
        copy(
            currentHeadY = headY,
            currentShouldersY = shouldersY,
            currentHipsY = hipsY,
            currentKneesY = kneesY,
            currentHipsX = hipsX,
            currentKneesX = kneesX
        )

    /**
     * Create a new state with updated lowest bar position.
     */
    fun withLowestBarPosition(position: Float?): ExercisePhaseState =
        copy(lowestBarPosition = position)

    /**
     * Create a new state with updated wrist positions (bench press).
     */
    fun withWristPositions(wristsY: Float?, wristsX: Float? = null): ExercisePhaseState =
        copy(currentWristsY = wristsY, currentWristsX = wristsX)

    /**
     * Create a new state with updated elbow data (bench press).
     */
    fun withElbowData(elbowsY: Float?, elbowAngle: Float? = null): ExercisePhaseState =
        copy(currentElbowsY = elbowsY, currentElbowAngle = elbowAngle)

    /**
     * Create a new state with starting wrist Y position (bench press).
     */
    fun withStartingWristsY(wristsY: Float?): ExercisePhaseState =
        copy(startingWristsY = wristsY)

    /**
     * Create a new state with lowest wrist Y position (bench press bottom).
     */
    fun withLowestWristsY(position: Float?): ExercisePhaseState =
        copy(lowestWristsY = position)

    /**
     * Create a new state with highest hip Y position (deadlift lockout).
     */
    fun withHighestHipsY(position: Float?): ExercisePhaseState =
        copy(highestHipsY = position)

    /**
     * Create a new state with starting torso angle (deadlift back rounding reference).
     */
    fun withStartingTorsoAngle(angle: Float?): ExercisePhaseState =
        copy(startingTorsoAngle = angle)

    /**
     * Create a new state with updated ankle positions (deadlift).
     */
    fun withAnklePositions(anklesY: Float?, anklesX: Float? = null): ExercisePhaseState =
        copy(currentAnklesY = anklesY, currentAnklesX = anklesX)

    /**
     * Create a new state with updated shoulder X position (deadlift bar drift).
     */
    fun withShouldersX(shouldersX: Float?): ExercisePhaseState =
        copy(currentShouldersX = shouldersX)

    /**
     * Create a new state with updated Olympic lift tracking data.
     */
    fun withOlympicLiftData(
        peakPullVelocity: Float? = this.peakPullVelocity,
        catchPosition: Float? = this.catchPosition,
        firstPullStartTime: Long? = this.firstPullStartTime,
        secondPullStartTime: Long? = this.secondPullStartTime,
        catchTime: Long? = this.catchTime
    ): ExercisePhaseState = copy(
        peakPullVelocity = peakPullVelocity,
        catchPosition = catchPosition,
        firstPullStartTime = firstPullStartTime,
        secondPullStartTime = secondPullStartTime,
        catchTime = catchTime
    )

    /**
     * Create a new state with updated previous frame positions (Olympic lift velocity calculation).
     */
    fun withPreviousPositions(
        wristsY: Float? = this.previousWristsY,
        hipsY: Float? = this.previousHipsY,
        timestamp: Long? = this.previousTimestamp
    ): ExercisePhaseState = copy(
        previousWristsY = wristsY,
        previousHipsY = hipsY,
        previousTimestamp = timestamp
    )

    companion object {
        /**
         * Creates a fresh initial state for a new workout/exercise.
         */
        fun initial(): ExercisePhaseState = ExercisePhaseState()
    }
}
