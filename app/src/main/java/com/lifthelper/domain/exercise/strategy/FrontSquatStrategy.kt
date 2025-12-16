package com.lifthelper.domain.exercise.strategy

import com.lifthelper.domain.exercise.ExerciseCriteria
import com.lifthelper.domain.exercise.ExerciseMetrics
import com.lifthelper.domain.exercise.ExercisePhaseState
import com.lifthelper.domain.exercise.ExerciseStrategy
import com.lifthelper.domain.exercise.FormIssue
import com.lifthelper.domain.exercise.FormValidationResult
import com.lifthelper.domain.exercise.PhaseTransitionResult
import com.lifthelper.domain.exercise.Severity
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.domain.value.Joules
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.value.Watts
import com.lifthelper.domain.vision.IPositionCalculator
import com.lifthelper.vision.tracking.MovementPhase
import javax.inject.Inject
import kotlin.math.abs

/**
 * Strategy for front squat exercise.
 *
 * Similar to back squat but with key differences:
 * 1. Bar position is on front shoulders (not upper back)
 * 2. More upright torso required for proper form
 * 3. Slightly different depth requirements due to bar position
 *
 * Phase detection criteria:
 * 1. ECCENTRIC: Head/shoulders descend > 30cm from starting position
 * 2. BOTTOM: Hips at or below knee level (proper squat depth)
 * 3. CONCENTRIC: Return to within ~15cm of starting position
 */
class FrontSquatStrategy @Inject constructor(
    private val positionCalculator: IPositionCalculator,
    private val logger: Logger
) : ExerciseStrategy {

    override val exerciseType = ExerciseType.FRONT_SQUAT

    private val criteria = ExerciseCriteria(
        minDescentDepth = Meters(0.30f),  // 30cm minimum descent
        returnTolerance = Meters(0.15f),  // 15cm tolerance for return
        minVelocity = MetersPerSecond(0.1f),
        targetBodyParts = listOf(
            PoseResult.NOSE,
            PoseResult.LEFT_SHOULDER,
            PoseResult.RIGHT_SHOULDER,
            PoseResult.LEFT_HIP,
            PoseResult.RIGHT_HIP,
            PoseResult.LEFT_KNEE,
            PoseResult.RIGHT_KNEE,
            PoseResult.LEFT_ELBOW,
            PoseResult.RIGHT_ELBOW  // Important for front squat form
        )
    )

    companion object {
        private const val TAG = "FrontSquatStrategy"
        private const val HIP_KNEE_ALIGNMENT_TOLERANCE = 0.05f // 5cm tolerance
    }

    override fun detectPhase(
        poseResult: PoseResult,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState
    ): PhaseTransitionResult {
        // Get body part positions
        val bodyPositions = positionCalculator.getBodyPartPositions(poseResult)
        if (bodyPositions == null) {
            return PhaseTransitionResult(
                newState = currentState,
                repCompleted = false,
                debugInfo = "No body positions detected"
            )
        }

        // Convert pixel positions to meters
        val currentHeadY = bodyPositions.headY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentShouldersY = bodyPositions.shouldersY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentHipsY = bodyPositions.hipsY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentKneesY = bodyPositions.kneesY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }

        // Update state with current positions
        val updatedState = currentState.withCurrentPositions(
            currentHeadY,
            currentShouldersY,
            currentHipsY,
            currentKneesY
        )

        // For front squat, bar position is at shoulders
        // We'll use shoulders as primary reference, with head as backup
        val barPosition = currentShouldersY ?: currentHeadY

        // State machine for phase detection
        return when (updatedState.currentPhase) {
            MovementPhase.IDLE -> detectIdleTransition(updatedState, barPosition)
            MovementPhase.ECCENTRIC -> detectEccentricTransition(updatedState, barPosition)
            MovementPhase.BOTTOM -> detectBottomTransition(updatedState, barPosition)
            MovementPhase.CONCENTRIC -> detectConcentricTransition(updatedState, barPosition)
            MovementPhase.TOP -> detectTopTransition(updatedState, barPosition)
        }
    }

    private fun detectIdleTransition(state: ExercisePhaseState, barPosition: Float?): PhaseTransitionResult {
        if (barPosition == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "No bar position"
            )
        }

        // Set starting position when we have stable data
        if (state.startingShouldersY == null) {
            val newState = state.withStartingPosition(
                state.currentHeadY,
                state.currentShouldersY,
                state.currentHipsY
            ).withLowestBarPosition(barPosition)

            logger.d(TAG, "Starting position set: bar=%.2f m, shoulders=%.2f m".format(
                barPosition, state.currentShouldersY))
            return PhaseTransitionResult(
                newState = newState,
                repCompleted = false,
                debugInfo = "Starting position set"
            )
        }

        val startingBarPosition = state.startingShouldersY ?: state.startingHeadY ?: barPosition
        val displacement = barPosition - startingBarPosition

        // Start eccentric phase if moving down more than 5cm
        if (displacement > 0.05f) {
            logger.d(TAG, "Phase: IDLE -> ECCENTRIC (displacement: %.2f m)".format(displacement))
            val newState = state
                .withCurrentPhase(MovementPhase.ECCENTRIC)
                .withLowestBarPosition(barPosition)
            return PhaseTransitionResult(
                newState = newState,
                repCompleted = false,
                debugInfo = "Descent started: %.2f m".format(displacement)
            )
        }

        return PhaseTransitionResult(
            newState = state,
            repCompleted = false,
            debugInfo = "Waiting for movement"
        )
    }

    private fun detectEccentricTransition(state: ExercisePhaseState, barPosition: Float?): PhaseTransitionResult {
        if (barPosition == null || state.startingShouldersY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val startingBarPosition = state.startingShouldersY!!
        val descent = barPosition - startingBarPosition

        // Track lowest position
        val updatedState = if (state.lowestBarPosition == null || barPosition > state.lowestBarPosition!!) {
            state.withLowestBarPosition(barPosition)
        } else {
            state
        }

        val sufficientDepth = descent >= criteria.minDescentDepth.value
        val hipsAtDepth = checkSquatDepth(state)

        logger.d(TAG, "ECCENTRIC: descent=%.2f m, sufficientDepth=$sufficientDepth, hipsAtDepth=$hipsAtDepth".format(descent))

        if (sufficientDepth && hipsAtDepth) {
            logger.d(TAG, "Phase: ECCENTRIC -> BOTTOM")
            return PhaseTransitionResult(
                newState = updatedState.withCurrentPhase(MovementPhase.BOTTOM),
                repCompleted = false,
                debugInfo = "Reached bottom: %.2f m descent".format(descent)
            )
        }

        return PhaseTransitionResult(
            newState = updatedState,
            repCompleted = false,
            debugInfo = "Descending: %.2f m".format(descent)
        )
    }

    private fun detectBottomTransition(state: ExercisePhaseState, barPosition: Float?): PhaseTransitionResult {
        if (barPosition == null || state.lowestBarPosition == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        // Check if starting to ascend (bar moving up by at least 3cm from lowest point)
        val ascent = state.lowestBarPosition!! - barPosition

        logger.d(TAG, "BOTTOM: ascent=%.2f m".format(ascent))

        if (ascent > 0.03f) {
            logger.d(TAG, "Phase: BOTTOM -> CONCENTRIC")
            return PhaseTransitionResult(
                newState = state.withCurrentPhase(MovementPhase.CONCENTRIC),
                repCompleted = false,
                debugInfo = "Ascent started: %.2f m".format(ascent)
            )
        }

        return PhaseTransitionResult(
            newState = state,
            repCompleted = false,
            debugInfo = "At bottom"
        )
    }

    private fun detectConcentricTransition(state: ExercisePhaseState, barPosition: Float?): PhaseTransitionResult {
        if (barPosition == null || state.startingShouldersY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val startingBarPosition = state.startingShouldersY!!
        val returnDistance = abs(barPosition - startingBarPosition)

        logger.d(TAG, "CONCENTRIC: returnDist=%.2f m".format(returnDistance))

        // Check if returned to starting position (within tolerance)
        if (returnDistance <= criteria.returnTolerance.value) {
            logger.d(TAG, "Phase: CONCENTRIC -> TOP (rep completed)")
            return PhaseTransitionResult(
                newState = state.withCurrentPhase(MovementPhase.TOP),
                repCompleted = true,
                debugInfo = "Rep completed"
            )
        }

        return PhaseTransitionResult(
            newState = state,
            repCompleted = false,
            debugInfo = "Ascending: %.2f m from start".format(returnDistance)
        )
    }

    private fun detectTopTransition(state: ExercisePhaseState, barPosition: Float?): PhaseTransitionResult {
        if (barPosition == null || state.startingShouldersY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val startingBarPosition = state.startingShouldersY!!
        val descent = barPosition - startingBarPosition

        // Check if starting new rep (descending again)
        if (descent > 0.05f) {
            logger.d(TAG, "Phase: TOP -> ECCENTRIC (new rep)")
            return PhaseTransitionResult(
                newState = state
                    .withCurrentPhase(MovementPhase.ECCENTRIC)
                    .withLowestBarPosition(barPosition),
                repCompleted = false,
                debugInfo = "New rep started"
            )
        }

        // Check if stabilized at top (ready for next rep)
        if (abs(descent) < 0.02f) {
            logger.d(TAG, "Phase: TOP -> IDLE")
            return PhaseTransitionResult(
                newState = state.withCurrentPhase(MovementPhase.IDLE),
                repCompleted = false,
                debugInfo = "Stabilized at top"
            )
        }

        return PhaseTransitionResult(
            newState = state,
            repCompleted = false,
            debugInfo = "At top position"
        )
    }

    /**
     * Check if squat depth is adequate (hips at or below knees)
     */
    private fun checkSquatDepth(state: ExercisePhaseState): Boolean {
        if (state.currentHipsY == null || state.currentKneesY == null) {
            // If we can't verify depth, assume it's OK to avoid false negatives
            return true
        }

        // In normalized coordinates with Y-axis pointing down:
        // Hips Y >= Knees Y means hips are at or below knees
        val depthOK = state.currentHipsY!! >= (state.currentKneesY!! - HIP_KNEE_ALIGNMENT_TOLERANCE)

        if (depthOK) {
            logger.d(TAG, "Squat depth OK: hips=%.2f m, knees=%.2f m".format(state.currentHipsY, state.currentKneesY))
        }

        return depthOK
    }

    override fun calculateMetrics(
        poseResult: PoseResult,
        velocityData: VelocityData,
        weight: Kilograms
    ): ExerciseMetrics {
        // TODO: This method needs refactoring to accept ExercisePhaseState
        // For now, returning basic metrics from velocity data only

        // Peak velocity is from velocity data
        val peakVelocity = velocityData.instantaneous.abs()

        // Calculate power: P = F * v = (m * g) * v
        val force = weight.value * 9.81f // Force in Newtons
        val avgPower = Watts(force * velocityData.average.abs().value)
        val peakPower = Watts(force * peakVelocity.value)

        // Calculate work using position from velocity data
        val displacement = velocityData.position
        val work = Joules(force * displacement.value)

        return ExerciseMetrics(
            displacement = displacement,
            peakVelocity = peakVelocity,
            avgPower = avgPower,
            peakPower = peakPower,
            work = work
        )
    }

    override fun validateForm(
        poseResult: PoseResult,
        calibrationData: CalibrationData
    ): FormValidationResult {
        // TODO: This method needs refactoring to accept ExercisePhaseState
        // For now, returning basic validation based on current frame only
        val issues = mutableListOf<FormIssue>()

        // Note: Full form validation requires access to ExercisePhaseState
        // which should be passed from the caller

        return FormValidationResult(
            isValid = true,
            issues = issues
        )
    }

    override fun getRequiredLandmarks(): List<Int> = criteria.targetBodyParts

    override fun getCriteria(): ExerciseCriteria = criteria
}
