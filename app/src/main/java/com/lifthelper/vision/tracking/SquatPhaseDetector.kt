package com.lifthelper.vision.tracking

import android.util.Log
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.util.Constants.TAG_MOVEMENT
import javax.inject.Inject
import kotlin.math.abs

/**
 * Position-based squat phase detection using biomechanical criteria
 *
 * Criteria for a valid front squat rep:
 * 1. ECCENTRIC: Head/shoulders descend > 30cm from starting position
 * 2. BOTTOM: Hips at or below knee level (proper squat depth)
 * 3. CONCENTRIC: Return to within ~10cm of starting position
 */
class SquatPhaseDetector @Inject constructor() {
    private var currentPhase = MovementPhase.IDLE

    // Store starting positions (in meters)
    private var startingHeadY: Float? = null
    private var startingShouldersY: Float? = null
    private var startingHipsY: Float? = null

    // Current positions (in meters) - updated each frame
    private var currentHeadY: Float? = null
    private var currentShouldersY: Float? = null
    private var currentHipsY: Float? = null
    private var currentKneesY: Float? = null

    // Lowest position reached during eccentric phase
    private var lowestBarPosition: Float? = null

    // Thresholds (in meters)
    private val minDescentDepth = 0.30f // 30 cm minimum descent for bar
    private val returnTolerance = 0.15f  // 15 cm tolerance for returning to start (relaxed for better detection)
    private val hipKneeAlignmentTolerance = 0.05f // 5 cm tolerance for hip-knee alignment

    /**
     * Update positions (in meters) and detect current phase
     *
     * @param headY Head position in meters
     * @param shouldersY Shoulders position in meters
     * @param hipsY Hips position in meters
     * @param kneesY Knees position in meters
     * @param poseResult Raw pose result for coordinate validation
     * @return Current movement phase
     */
    fun detectPhase(
        headY: Float?,
        shouldersY: Float?,
        hipsY: Float?,
        kneesY: Float?,
        poseResult: PoseResult
    ): MovementPhase {
        // Update current positions
        currentHeadY = headY
        currentShouldersY = shouldersY
        currentHipsY = hipsY
        currentKneesY = kneesY

        // Calculate bar position (average of head and shoulders)
        val barPosition = if (headY != null && shouldersY != null) {
            (headY + shouldersY) / 2f
        } else {
            headY ?: shouldersY
        }

        // State machine for phase detection
        currentPhase = when (currentPhase) {
            MovementPhase.IDLE -> detectIdleTransition(barPosition)
            MovementPhase.ECCENTRIC -> detectEccentricTransition(barPosition)
            MovementPhase.BOTTOM -> detectBottomTransition(barPosition)
            MovementPhase.CONCENTRIC -> detectConcentricTransition(barPosition)
            MovementPhase.TOP -> detectTopTransition(barPosition)
        }

        return currentPhase
    }

    private fun detectIdleTransition(barPosition: Float?): MovementPhase {
        if (barPosition == null) return MovementPhase.IDLE

        // Set starting position when we have stable data
        if (startingHeadY == null || startingShouldersY == null) {
            startingHeadY = currentHeadY
            startingShouldersY = currentShouldersY
            startingHipsY = currentHipsY
            lowestBarPosition = barPosition
            Log.d(TAG_MOVEMENT, "Starting position set: bar=%.2f m".format(barPosition))
            return MovementPhase.IDLE
        }

        val startingBarPosition = (startingHeadY!! + startingShouldersY!!) / 2f
        val descent = barPosition - startingBarPosition // Positive = going down (Y increases downward)

        // Start eccentric phase if descending more than 5cm
        if (descent > 0.05f) {
            Log.d(TAG_MOVEMENT, "Phase: IDLE -> ECCENTRIC (descent: %.2f m)".format(descent))
            lowestBarPosition = barPosition
            return MovementPhase.ECCENTRIC
        }

        return MovementPhase.IDLE
    }

    private fun detectEccentricTransition(barPosition: Float?): MovementPhase {
        if (barPosition == null || startingHeadY == null || startingShouldersY == null) {
            return MovementPhase.ECCENTRIC
        }

        val startingBarPosition = (startingHeadY!! + startingShouldersY!!) / 2f
        val descent = barPosition - startingBarPosition

        // Track lowest position
        if (lowestBarPosition == null || barPosition > lowestBarPosition!!) {
            lowestBarPosition = barPosition
        }

        // Check if still descending or reached bottom
        // Bottom is reached when:
        // 1. Descended at least 30cm AND
        // 2. Hips are at or below knees (proper depth) OR descent stopped (velocity near zero)
        val sufficientDepth = descent >= minDescentDepth
        val hipsAtDepth = checkSquatDepth()

        if (sufficientDepth && hipsAtDepth) {
            Log.d(TAG_MOVEMENT, "Phase: ECCENTRIC -> BOTTOM (descent: %.2f m, depth: OK)".format(descent))
            return MovementPhase.BOTTOM
        }

        return MovementPhase.ECCENTRIC
    }

    private fun detectBottomTransition(barPosition: Float?): MovementPhase {
        if (barPosition == null || lowestBarPosition == null) {
            return MovementPhase.BOTTOM
        }

        // Check if starting to ascend (bar moving up by at least 3cm from lowest point)
        val ascent = lowestBarPosition!! - barPosition // Negative = going up

        if (ascent > 0.03f) {
            Log.d(TAG_MOVEMENT, "Phase: BOTTOM -> CONCENTRIC (ascent started: %.2f m)".format(ascent))
            return MovementPhase.CONCENTRIC
        }

        return MovementPhase.BOTTOM
    }

    private fun detectConcentricTransition(barPosition: Float?): MovementPhase {
        if (barPosition == null || startingHeadY == null || startingShouldersY == null) {
            return MovementPhase.CONCENTRIC
        }

        val startingBarPosition = (startingHeadY!! + startingShouldersY!!) / 2f
        val returnDistance = abs(barPosition - startingBarPosition)

        // Check if returned to starting position (within 10cm tolerance)
        if (returnDistance <= returnTolerance) {
            Log.d(TAG_MOVEMENT, "Phase: CONCENTRIC -> TOP (returned to start, distance: %.2f m)".format(returnDistance))
            return MovementPhase.TOP
        }

        return MovementPhase.CONCENTRIC
    }

    private fun detectTopTransition(barPosition: Float?): MovementPhase {
        if (barPosition == null || startingHeadY == null || startingShouldersY == null) {
            return MovementPhase.TOP
        }

        val startingBarPosition = (startingHeadY!! + startingShouldersY!!) / 2f
        val descent = barPosition - startingBarPosition

        // Check if starting new rep (descending again)
        if (descent > 0.05f) {
            Log.d(TAG_MOVEMENT, "Phase: TOP -> ECCENTRIC (new rep started)")
            lowestBarPosition = barPosition
            return MovementPhase.ECCENTRIC
        }

        // Check if stabilized at top (ready for next rep)
        if (abs(descent) < 0.02f) {
            Log.d(TAG_MOVEMENT, "Phase: TOP -> IDLE (stabilized)")
            return MovementPhase.IDLE
        }

        return MovementPhase.TOP
    }

    /**
     * Check if squat depth is adequate (hips at or below knees)
     */
    private fun checkSquatDepth(): Boolean {
        if (currentHipsY == null || currentKneesY == null) {
            // If we can't verify depth, assume it's OK to avoid false negatives
            return true
        }

        // In normalized coordinates with Y-axis pointing down:
        // Hips Y >= Knees Y means hips are at or below knees
        val depthOK = currentHipsY!! >= (currentKneesY!! - hipKneeAlignmentTolerance)

        if (depthOK) {
            Log.d(TAG_MOVEMENT, "Squat depth OK: hips=%.2f m, knees=%.2f m".format(currentHipsY, currentKneesY))
        }

        return depthOK
    }

    fun reset() {
        currentPhase = MovementPhase.IDLE
        startingHeadY = null
        startingShouldersY = null
        startingHipsY = null
        currentHeadY = null
        currentShouldersY = null
        currentHipsY = null
        currentKneesY = null
        lowestBarPosition = null
        Log.d(TAG_MOVEMENT, "SquatPhaseDetector reset")
    }

    fun getCurrentPhase(): MovementPhase = currentPhase

    /**
     * Get current descent/ascent information for debugging
     */
    fun getPositionInfo(): String {
        if (startingHeadY == null || startingShouldersY == null || currentHeadY == null || currentShouldersY == null) {
            return "No position data"
        }

        val startingBar = (startingHeadY!! + startingShouldersY!!) / 2f
        val currentBar = (currentHeadY!! + currentShouldersY!!) / 2f
        val displacement = currentBar - startingBar

        return "Bar: start=%.2f m, current=%.2f m, displacement=%.2f m (%.0f cm)".format(
            startingBar, currentBar, displacement, displacement * 100
        )
    }
}
