package com.liftr.domain.model

import com.liftr.domain.value.MetersPerSecond

/**
 * A single frame's bar position with velocity context.
 *
 * @property normalizedX X position in normalized screen coordinates (0.0-1.0)
 * @property normalizedY Y position in normalized screen coordinates (0.0-1.0)
 * @property realWorldXCm X position in centimeters (calibrated)
 * @property realWorldYCm Y position in centimeters (calibrated)
 * @property velocity Instantaneous velocity at this point
 * @property timestamp Frame timestamp in milliseconds
 */
data class BarPathPoint(
    val normalizedX: Float,
    val normalizedY: Float,
    val realWorldXCm: Float,
    val realWorldYCm: Float,
    val velocity: MetersPerSecond,
    val timestamp: Long
)

/**
 * Analyzed bar path for a single rep.
 *
 * @property repNumber 1-indexed rep number within the set
 * @property points All captured bar positions for this rep
 * @property maxDriftCm Maximum horizontal drift from the starting X position (cm)
 * @property linearityScore Path linearity score (0-100, 100 = perfectly vertical)
 * @property driftDirection Which direction the bar drifted on average
 * @property excursion For jumps, how far the center of mass travelled from standing
 */
data class BarPathData(
    val repNumber: Int,
    val points: List<BarPathPoint>,
    val maxDriftCm: Float,
    val linearityScore: Int,
    val driftDirection: DriftDirection,
    val excursion: JumpExcursion? = null
) {
    val isValid: Boolean get() = points.size >= 3
}

/**
 * A jump's center-of-mass travel relative to the athlete standing still.
 *
 * @property countermovementCm Depth of the dip below standing before takeoff
 * @property peakRiseCm Highest point of the flight above standing
 * @property standingYCm Standing height in the same units as [BarPathPoint.realWorldYCm]
 * @property flightRiseCm Rise from takeoff to the top of the flight: the jump
 *   height measured from center-of-mass displacement instead of flight time
 * @property takeoffVelocity Upward center-of-mass velocity at takeoff, in m/s
 * @property velocityHeightCm Jump height from [takeoffVelocity]: v² / 2g
 * @property fitCheck Whether the flight looked like free fall
 */
data class JumpExcursion(
    val countermovementCm: Float,
    val peakRiseCm: Float,
    val standingYCm: Float,
    val flightRiseCm: Float? = null,
    val takeoffVelocity: Float? = null,
    val velocityHeightCm: Float? = null,
    val fitCheck: JumpFitCheck? = null
)

enum class DriftDirection(val label: String) {
    FORWARD("Forward"),
    BACKWARD("Backward"),
    NONE("Centered")
}
