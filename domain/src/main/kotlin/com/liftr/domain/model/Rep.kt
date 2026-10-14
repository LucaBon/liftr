package com.liftr.domain.model

import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.Percentage

/**
 * Domain model representing a single repetition of an exercise.
 *
 * This is a pure Kotlin data class with no Android dependencies,
 * containing detailed biomechanical data for the repetition.
 *
 * @property id Unique identifier for the rep (0 if not yet saved)
 * @property setId The ID of the parent set
 * @property repNumber The rep number within the set (1-indexed)
 * @property peakVelocity Peak velocity achieved during this rep
 * @property avgVelocity Average velocity during this rep
 * @property timeUnderTension Total time under tension in seconds
 * @property rangeOfMotion Range of motion covered
 * @property startPosition Starting position
 * @property endPosition Ending position
 * @property eccentricDuration Duration of the eccentric (lowering) phase in seconds
 * @property concentricDuration Duration of the concentric (lifting) phase in seconds
 * @property velocityProfile Frame-by-frame velocity data
 * @property positionProfile Frame-by-frame position data
 * @property timestamp Timestamp when the rep was completed
 * @property qualityScore Quality score for this rep (0-100), 0 = not scored
 * @property jump What a jump measured (jump exercises only)
 */
data class Rep(
    val id: Long = 0,
    val setId: Long,
    val repNumber: Int,
    val peakVelocity: MetersPerSecond,
    val avgVelocity: MetersPerSecond,
    val timeUnderTension: Float,
    val rangeOfMotion: Meters,
    val startPosition: Meters,
    val endPosition: Meters,
    val eccentricDuration: Float,
    val concentricDuration: Float,
    val velocityProfile: List<VelocityProfile>,
    val positionProfile: List<PositionProfile>,
    val timestamp: Long,
    val qualityScore: Int = 0,
    val jump: RepJumpData? = null
) {
    /**
     * Returns the total duration of the rep in seconds.
     */
    fun getTotalDuration(): Float = eccentricDuration + concentricDuration

    /**
     * Returns the ratio of eccentric to concentric duration.
     */
    fun getEccentricConcentricRatio(): Float {
        if (concentricDuration == 0f) return 0f
        return eccentricDuration / concentricDuration
    }

    /**
     * Returns whether this rep showed good velocity (>= 80% of peak).
     */
    fun hasGoodVelocity(): Boolean {
        if (peakVelocity.value == 0f) return false
        return avgVelocity >= (peakVelocity * 0.8f)
    }
}
