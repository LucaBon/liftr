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
 * @property toeTracks Approach jumps: each visible toe with its position, since
 *   when it has been planted and its heights while planted; matched from frame
 *   to frame by position, as left and right labels swap when filmed side-on
 * @property followedToe Approach jumps: the toe whose height is measured, the
 *   one last planted (with [followedVelocity] in units per second to predict it in flight)
 * @property planted Approach jumps: whether any toe is in stance on this frame
 * @property previousHipsY Hip height on the previous frame, to tell hips driving up
 * @property previousTimestamp Timestamp of the previous frame (ms)
 * @property stillFootY Toe height the feet have been resting at
 * @property stillHipsY Hip height the hips have been resting at
 * @property stillSinceMs When feet and hips came to rest at those heights
 * @property upsideDown Whether the baseline pose had the head below the feet
 *   (camera upside down); frames the other way up are mistracked
 * @property flippedSinceMs Since when frames have been the other way up from
 *   the baseline, back to back; for long enough, the camera itself was turned
 * @property contactStartMs When the feet touched the floor at the end of the
 *   last counted jump (its landing crossing moved down to the floor, see
 *   [com.liftr.domain.model.JumpData.contactTimeMs]); the next takeoff times
 *   its ground contact from here. Null before the first jump, after a flight
 *   that wasn't counted, and once the athlete has rested for STILL_MS.
 */
data class JumpTrackingState(
    val baselineFootY: Float,
    val baselineHipsY: Float,
    val bodySpan: Float,
    val lowestHipsY: Float? = null,
    val takeoffCandidateMs: Double? = null,
    val takeoffMs: Double? = null,
    val flightSamples: List<Pair<Double, Float>> = emptyList(),
    val groundSamples: List<Pair<Double, Float>> = emptyList(),
    val flightHipSamples: List<Pair<Double, Float>> = emptyList(),
    val peakHeadRise: Float = 0f,
    val previousFootY: Float? = null,
    val toeTracks: List<ToeTrack> = emptyList(),
    val followedToe: ToePoint? = null,
    val followedVelocity: ToePoint? = null,
    val planted: Boolean = false,
    val previousHipsY: Float? = null,
    val previousTimestamp: Double? = null,
    val stillFootY: Float? = null,
    val stillHipsY: Float? = null,
    val stillSinceMs: Double? = null,
    val upsideDown: Boolean = false,
    val flippedSinceMs: Double? = null,
    val contactStartMs: Double? = null
) {
    val isAirborne: Boolean get() = takeoffMs != null
}

/** A toe in the image: x in units of the image height, y with larger = lower. */
data class ToePoint(val x: Float, val y: Float)

/** A toe followed across frames; [slowSinceMs] is null while it moves, [stanceYs] its recent heights while planted. */
data class ToeTrack(
    val x: Float,
    val y: Float,
    val slowSinceMs: Double? = null,
    val stanceYs: List<Float> = emptyList(),
    /** When each of [stanceYs] was seen, in ms. */
    val stanceTimesMs: List<Double> = emptyList()
)
