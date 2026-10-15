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
 * @property groundSamples (timestamp ms, toe rise) of the frames on the ground
 *   in the last 300 ms, to find the first frame off it and the floor level
 * @property flightHipSamples (timestamp ms, hip rise) of the airborne frames,
 *   for finding the top of the flight
 * @property peakHeadRise Highest head rise above standing during the flight
 * @property previousFootY Toe height on the previous frame, for interpolation
 * @property previousTimestamp Timestamp of the previous frame (ms)
 * @property stillFootY Toe height the feet have been resting at
 * @property stillHipsY Hip height the hips have been resting at
 * @property stillSinceMs When feet and hips came to rest at those heights
 * @property upsideDown Whether the baseline pose had the head below the feet
 *   (camera upside down); frames the other way up are mistracked
 * @property flippedSinceMs Since when frames have been the other way up from
 *   the baseline, back to back; for long enough, the camera itself was turned
 */
data class JumpTrackingState(
    val baselineFootY: Float,
    val baselineHipsY: Float,
    val bodySpan: Float,
    val lowestHipsY: Float? = null,
    val takeoffCandidateMs: Double? = null,
    val takeoffMs: Double? = null,
    val flightSamples: List<Pair<Long, Float>> = emptyList(),
    val groundSamples: List<Pair<Long, Float>> = emptyList(),
    val flightHipSamples: List<Pair<Long, Float>> = emptyList(),
    val peakHeadRise: Float = 0f,
    val previousFootY: Float? = null,
    val previousTimestamp: Long? = null,
    val stillFootY: Float? = null,
    val stillHipsY: Float? = null,
    val stillSinceMs: Long? = null,
    val upsideDown: Boolean = false,
    val flippedSinceMs: Long? = null
) {
    val isAirborne: Boolean get() = takeoffMs != null
}
