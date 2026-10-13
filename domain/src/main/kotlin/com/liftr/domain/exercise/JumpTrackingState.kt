package com.liftr.domain.exercise

/**
 * Per-set tracking state for jump detection, held in [ExercisePhaseState.jump].
 *
 * All Y values are normalized image coordinates oriented so that larger = lower.
 *
 * @property baselineFootY Standing toe height (the ground)
 * @property baselineHipsY Standing hip height
 * @property bodySpan Standing head-to-toe span; thresholds are fractions of it
 * @property lowestHipsY Deepest hip position of the current countermovement
 * @property takeoffCandidateMs Interpolated time the toes crossed the timing
 *   level going up; confirmed as takeoff once the feet clear the airborne level
 * @property takeoffMs Confirmed takeoff time while airborne, null on the ground
 * @property flightSamples (timestamp ms, toe rise) of the airborne frames, for
 *   fitting the flight parabola
 * @property previousFootY Toe height on the previous frame, for interpolation
 * @property previousTimestamp Timestamp of the previous frame (ms)
 */
data class JumpTrackingState(
    val baselineFootY: Float,
    val baselineHipsY: Float,
    val bodySpan: Float,
    val lowestHipsY: Float? = null,
    val takeoffCandidateMs: Double? = null,
    val takeoffMs: Double? = null,
    val flightSamples: List<Pair<Long, Float>> = emptyList(),
    val previousFootY: Float? = null,
    val previousTimestamp: Long? = null
) {
    val isAirborne: Boolean get() = takeoffMs != null
}
