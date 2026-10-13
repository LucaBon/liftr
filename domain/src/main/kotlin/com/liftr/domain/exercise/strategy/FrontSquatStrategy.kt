package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.ExerciseCriteria
import com.liftr.domain.exercise.ExerciseMetrics
import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseStrategy
import com.liftr.domain.exercise.ExerciseThresholds
import com.liftr.domain.exercise.FormIssue
import com.liftr.domain.exercise.FormValidationResult
import com.liftr.domain.exercise.PhaseTransitionResult
import com.liftr.domain.exercise.Severity
import com.liftr.domain.exercise.SquatDepthValidator
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.DepthValidationResult
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.SquatDepthType
import com.liftr.domain.model.VelocityData
import com.liftr.domain.value.Joules
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.Watts
import com.liftr.domain.vision.IPositionCalculator
import com.liftr.domain.model.BodyPartPositions
import com.liftr.domain.model.MovementPhase
import java.util.concurrent.atomic.AtomicReference
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

    // Thread-safe: updated by settings changes, read during frame processing (BUG-B fix)
    private val criteriaRef = AtomicReference(ExerciseCriteria(
        minDescentDepth = ExerciseThresholds.FrontSquat.MIN_DESCENT_DEPTH,
        returnTolerance = ExerciseThresholds.FrontSquat.RETURN_TOLERANCE,
        minVelocity = ExerciseThresholds.FrontSquat.MIN_VELOCITY,
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
        ),
        squatDepthType = SquatDepthType.DEFAULT
    ))

    companion object {
        private const val TAG = "FrontSquatStrategy"
    }

    /**
     * Updates the squat depth type requirement for this strategy.
     * This allows dynamic configuration based on user preferences.
     */
    fun updateSquatDepthType(depthType: SquatDepthType) {
        criteriaRef.updateAndGet { it.copy(squatDepthType = depthType) }
        logger.d(TAG, "Updated squat depth type to: ${depthType.displayName}")
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
        val currentHipsX = bodyPositions.hipsX?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentKneesX = bodyPositions.kneesX?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }

        // Update state with current positions (including X coordinates for femur angle)
        val updatedState = currentState.withCurrentPositions(
            headY = currentHeadY,
            shouldersY = currentShouldersY,
            hipsY = currentHipsY,
            kneesY = currentKneesY,
            hipsX = currentHipsX,
            kneesX = currentKneesX
        )

        // Calculate bar position for front squat (bar rests on front shoulders)
        val barPosition = calculateFrontSquatBarPosition(currentShouldersY, currentHeadY)

        // State machine for phase detection
        return when (updatedState.currentPhase) {
            MovementPhase.IDLE -> detectIdleTransition(updatedState, barPosition)
            MovementPhase.ECCENTRIC -> detectEccentricTransition(updatedState, barPosition)
            MovementPhase.BOTTOM -> detectBottomTransition(updatedState, barPosition)
            MovementPhase.CONCENTRIC -> detectConcentricTransition(updatedState, barPosition)
            MovementPhase.TOP -> detectTopTransition(updatedState, barPosition)
        }
    }

    /**
     * Calculate bar position for front squat.
     * Bar rests on front shoulders (deltoids), so shoulders position is primary.
     *
     * @param shouldersY Current shoulders Y position in meters
     * @param headY Current head Y position in meters (used as fallback)
     * @return Bar position in meters, or null if cannot be determined
     */
    private fun calculateFrontSquatBarPosition(shouldersY: Float?, headY: Float?): Float? {
        return when {
            shouldersY != null -> shouldersY  // Primary: shoulders (where bar rests)
            headY != null -> headY  // Fallback: head position
            else -> null
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

        // Start eccentric phase if moving down more than threshold
        if (displacement > ExerciseThresholds.FrontSquat.IDLE_TO_ECCENTRIC_THRESHOLD) {
            logger.d(TAG, "Phase: IDLE -> ECCENTRIC (displacement: %.2f m)".format(displacement))
            val newState = state
                .withCurrentPhase(MovementPhase.ECCENTRIC)
                .withLowestBarPosition(barPosition)
                .withDepthWindowSatisfied(false)
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
        val startingShouldersY = state.startingShouldersY

        if (barPosition == null || startingShouldersY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val startingBarPosition = startingShouldersY
        val descent = barPosition - startingBarPosition

        // Track lowest position
        val lowestBar = state.lowestBarPosition
        val updatedState = (if (lowestBar == null || barPosition > lowestBar) {
            state.withLowestBarPosition(barPosition)
        } else {
            state
        })

        // Descent required scales with the configured depth: a quarter squat
        // cannot travel as far as a parallel one.
        val criteria = criteriaRef.get()
        val requiredDescent = criteria.squatDepthType.minBarDescent.value
        val sufficientDepth = descent >= requiredDescent

        val depthResult = validateSquatDepth(state)
        // Latch the depth window: for the shallow depth types it is an upper
        // bound that a fast descent can cross entirely between two frames, so
        // requiring it on the same frame as the descent gate would drop reps.
        val windowSatisfiedNow = depthResult?.isValid ?: false
        val hipsAtDepth = windowSatisfiedNow || state.depthWindowSatisfied
        val latchedState = if (windowSatisfiedNow && !state.depthWindowSatisfied) {
            updatedState.withDepthWindowSatisfied(true)
        } else updatedState

        logger.d(TAG, "ECCENTRIC: descent=%.2f m, sufficientDepth=$sufficientDepth, hipsAtDepth=$hipsAtDepth".format(descent))

        if (sufficientDepth && hipsAtDepth) {
            logger.d(TAG, "Phase: ECCENTRIC -> BOTTOM")
            return PhaseTransitionResult(
                newState = latchedState.withCurrentPhase(MovementPhase.BOTTOM),
                repCompleted = false,
                debugInfo = "Reached bottom: %.2f m descent".format(descent),
                depthValidation = depthResult
            )
        }

        return PhaseTransitionResult(
            newState = latchedState,
            repCompleted = false,
            debugInfo = "Descending: %.2f m".format(descent)
        )
    }

    private fun detectBottomTransition(state: ExercisePhaseState, barPosition: Float?): PhaseTransitionResult {
        val lowestBar = state.lowestBarPosition

        if (barPosition == null || lowestBar == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        // Check if starting to ascend (bar moving up by at least 3cm from lowest point)
        val ascent = lowestBar - barPosition

        logger.d(TAG, "BOTTOM: ascent=%.2f m".format(ascent))

        if (ascent > ExerciseThresholds.FrontSquat.BOTTOM_TO_CONCENTRIC_THRESHOLD) {
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
        val startingShouldersY = state.startingShouldersY

        if (barPosition == null || startingShouldersY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val startingBarPosition = startingShouldersY
        val returnDistance = abs(barPosition - startingBarPosition)

        logger.d(TAG, "CONCENTRIC: returnDist=%.2f m".format(returnDistance))

        // Check if returned to starting position (within tolerance)
        if (returnDistance <= criteriaRef.get().returnTolerance.value) {
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
        val startingShouldersY = state.startingShouldersY

        if (barPosition == null || startingShouldersY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val startingBarPosition = startingShouldersY
        val descent = barPosition - startingBarPosition

        // Check if starting new rep (descending again)
        if (descent > ExerciseThresholds.FrontSquat.IDLE_TO_ECCENTRIC_THRESHOLD) {
            logger.d(TAG, "Phase: TOP -> ECCENTRIC (new rep)")
            return PhaseTransitionResult(
                newState = state
                    .withCurrentPhase(MovementPhase.ECCENTRIC)
                    .withLowestBarPosition(barPosition)
                    .withDepthWindowSatisfied(false),
                repCompleted = false,
                debugInfo = "New rep started"
            )
        }

        // Check if stabilized at top (ready for next rep)
        if (abs(descent) < ExerciseThresholds.FrontSquat.TOP_STABILIZATION_THRESHOLD) {
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
     * Validates squat depth using robust multi-method validation with confidence scoring.
     *
     * @return DepthValidationResult if sufficient landmark data is available, null otherwise
     */
    private fun validateSquatDepth(state: ExercisePhaseState): DepthValidationResult? {
        if (state.currentHipsY == null || state.currentKneesY == null) {
            logger.w(TAG, "Cannot verify squat depth - missing hip or knee landmarks")
            return null
        }

        val depthType = criteriaRef.get().squatDepthType

        val validationResult = SquatDepthValidator.validateDepth(
            state = state,
            depthType = depthType,
            tolerance = Meters(ExerciseThresholds.FrontSquat.DEPTH_TOLERANCE)
        )

        if (validationResult.isValid) {
            logger.d(TAG, "Squat depth PASS for ${depthType.displayName}")
        } else {
            logger.d(TAG, "Squat depth FAIL for ${depthType.displayName}")
        }
        logger.d(TAG, validationResult.debugInfo)

        return validationResult
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
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState?
    ): FormValidationResult {
        val issues = mutableListOf<FormIssue>()
        val landmarks = poseResult.landmarks

        // Get body part positions
        val bodyPositions = positionCalculator.getBodyPartPositions(poseResult)

        if (bodyPositions == null) {
            // Cannot validate without body positions
            return FormValidationResult(isValid = true, issues = issues)
        }

        // Knee valgus/varus can only be measured from a frontal view; the app
        // is framed laterally for squats, so that check is intentionally absent.

        // Check torso uprightness (critical for front squat)
        val torsoIssue = checkTorsoUprightness(landmarks, bodyPositions)
        if (torsoIssue != null) {
            issues.add(torsoIssue)
        }

        // 3. Check elbow position (elbows should be high)
        val elbowIssue = checkElbowPosition(landmarks)
        if (elbowIssue != null) {
            issues.add(elbowIssue)
        }

        // 4. Check foot stability (heel lift)
        val footIssue = checkFootStability(landmarks)
        if (footIssue != null) {
            issues.add(footIssue)
        }

        // Consider form valid if no CRITICAL severity issues
        val isValid = issues.none { it.severity == Severity.CRITICAL }

        return FormValidationResult(
            isValid = isValid,
            issues = issues
        )
    }

    /**
     * Check torso uprightness - critical for front squat.
     * Front squat requires more upright torso than back squat to prevent bar rolling off.
     */
    private fun checkTorsoUprightness(
        landmarks: List<com.liftr.domain.model.PoseKeypoint>,
        bodyPositions: BodyPartPositions
    ): FormIssue? {
        val shoulders = bodyPositions.shouldersY ?: return null
        val hips = bodyPositions.hipsY ?: return null
        val shouldersX = bodyPositions.shouldersX ?: return null
        val hipsX = bodyPositions.hipsX ?: return null

        // Calculate torso angle from vertical
        val torsoVerticalDistance = kotlin.math.abs(shoulders - hips)
        val torsoHorizontalDistance = kotlin.math.abs(shouldersX - hipsX)

        if (torsoVerticalDistance < 0.01f) return null  // Avoid division by zero

        // Angle from vertical in degrees (0° = perfectly upright)
        val angleFromVertical = Math.toDegrees(
            kotlin.math.atan2(torsoHorizontalDistance, torsoVerticalDistance).toDouble()
        ).toFloat()

        // Front squat requires more upright torso (stricter thresholds than back squat)
        return when {
            angleFromVertical > 35f -> FormIssue(
                severity = Severity.CRITICAL,
                message = "Excessive forward lean (${angleFromVertical.toInt()}°)",
                suggestion = "Keep chest up and elbows high to maintain upright torso"
            )
            angleFromVertical > 25f -> FormIssue(
                severity = Severity.WARNING,
                message = "Moderate forward lean (${angleFromVertical.toInt()}°)",
                suggestion = "Try to stay more upright - drive elbows up"
            )
            else -> null
        }
    }

    /**
     * Check elbow position - elbows should be high and parallel to ground.
     * This is critical for front squat to keep bar secure on shoulders.
     */
    private fun checkElbowPosition(landmarks: List<com.liftr.domain.model.PoseKeypoint>): FormIssue? {
        val leftElbow = landmarks.getOrNull(13)
        val rightElbow = landmarks.getOrNull(14)
        val leftShoulder = landmarks.getOrNull(11)
        val rightShoulder = landmarks.getOrNull(12)

        if (leftElbow == null || rightElbow == null ||
            leftShoulder == null || rightShoulder == null) {
            return null
        }

        if (leftElbow.visibility < 0.5f || rightElbow.visibility < 0.5f ||
            leftShoulder.visibility < 0.5f || rightShoulder.visibility < 0.5f) {
            return null
        }

        // Calculate average elbow and shoulder Y positions
        val avgElbowY = (leftElbow.y + rightElbow.y) / 2f
        val avgShoulderY = (leftShoulder.y + rightShoulder.y) / 2f

        // Elbows should be at or above shoulder level (smaller Y = higher)
        val elbowDrop = avgElbowY - avgShoulderY

        return when {
            elbowDrop > 0.15f -> FormIssue(
                severity = Severity.CRITICAL,
                message = "Elbows dropped significantly below shoulders",
                suggestion = "Drive elbows up and keep them high throughout the movement"
            )
            elbowDrop > 0.08f -> FormIssue(
                severity = Severity.WARNING,
                message = "Elbows lowering below optimal position",
                suggestion = "Focus on keeping elbows parallel to ground"
            )
            else -> null
        }
    }

    /**
     * Check foot stability - detect heel lift which compromises stability.
     */
    private fun checkFootStability(landmarks: List<com.liftr.domain.model.PoseKeypoint>): FormIssue? {
        val leftHeel = landmarks.getOrNull(29)
        val rightHeel = landmarks.getOrNull(30)
        val leftAnkle = landmarks.getOrNull(27)
        val rightAnkle = landmarks.getOrNull(28)

        if (leftHeel == null || rightHeel == null || leftAnkle == null || rightAnkle == null) {
            return null
        }

        // Check if heels are visible and stable
        val heelVisibility = (leftHeel.visibility + rightHeel.visibility) / 2f

        return if (heelVisibility < 0.3f) {
            FormIssue(
                severity = Severity.WARNING,
                message = "Heels lifting off ground",
                suggestion = "Keep entire foot flat on ground - may need to improve ankle mobility"
            )
        } else {
            null
        }
    }

    override fun getRequiredLandmarks(): List<Int> = criteriaRef.get().targetBodyParts

    override fun getCriteria(): ExerciseCriteria = criteriaRef.get()
}
