package com.lifthelper.domain.exercise.strategy

import com.lifthelper.domain.exercise.ExerciseCriteria
import com.lifthelper.domain.exercise.ExerciseMetrics
import com.lifthelper.domain.exercise.ExercisePhaseState
import com.lifthelper.domain.exercise.ExerciseStrategy
import com.lifthelper.domain.exercise.ExerciseThresholds
import com.lifthelper.domain.exercise.FormIssue
import com.lifthelper.domain.exercise.FormValidationResult
import com.lifthelper.domain.exercise.PhaseTransitionResult
import com.lifthelper.domain.exercise.Severity
import com.lifthelper.domain.exercise.SquatDepthValidator
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.DepthValidationResult
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.SquatDepthType
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.domain.value.Joules
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.value.Watts
import com.lifthelper.domain.vision.IPositionCalculator
import com.lifthelper.vision.calculator.BodyPartPositions
import com.lifthelper.vision.tracking.MovementPhase
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.math.abs

/**
 * Strategy for back squat exercise.
 *
 * Implements position-based phase detection using biomechanical criteria:
 * 1. ECCENTRIC: Head/shoulders descend > 30cm from starting position
 * 2. BOTTOM: Hips reach the configured squat depth (Quarter/Half/Powerlifting/Full)
 * 3. CONCENTRIC: Return to within ~15cm of starting position
 */
class BackSquatStrategy @Inject constructor(
    private val positionCalculator: IPositionCalculator,
    private val logger: Logger
) : ExerciseStrategy {

    override val exerciseType = ExerciseType.BACK_SQUAT

    // Thread-safe: updated by settings changes, read during frame processing (BUG-B fix)
    private val criteriaRef = AtomicReference(ExerciseCriteria(
        minDescentDepth = ExerciseThresholds.BackSquat.MIN_DESCENT_DEPTH,
        returnTolerance = ExerciseThresholds.BackSquat.RETURN_TOLERANCE,
        minVelocity = ExerciseThresholds.BackSquat.MIN_VELOCITY,
        targetBodyParts = listOf(
            PoseResult.NOSE,
            PoseResult.LEFT_SHOULDER,
            PoseResult.RIGHT_SHOULDER,
            PoseResult.LEFT_HIP,
            PoseResult.RIGHT_HIP,
            PoseResult.LEFT_KNEE,
            PoseResult.RIGHT_KNEE
        ),
        squatDepthType = SquatDepthType.DEFAULT
    ))

    companion object {
        private const val TAG = "BackSquatStrategy"
    }

    /**
     * Updates the squat depth type for this strategy.
     * This should be called when the user changes their depth preference in settings.
     *
     * @param depthType The new squat depth type to use
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

        // Calculate bar position for back squat (bar rests on upper back)
        val barPosition = calculateBackSquatBarPosition(currentHeadY, currentShouldersY)

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
     * Calculate bar position for back squat.
     * Bar rests on upper back, approximately midway between head and shoulders.
     *
     * @param headY Current head Y position in meters
     * @param shouldersY Current shoulders Y position in meters
     * @return Bar position in meters, or null if cannot be determined
     */
    private fun calculateBackSquatBarPosition(headY: Float?, shouldersY: Float?): Float? {
        return when {
            headY != null && shouldersY != null -> (headY + shouldersY) / 2f
            shouldersY != null -> shouldersY  // Prefer shoulders if only one available
            headY != null -> headY
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
        if (state.startingHeadY == null || state.startingShouldersY == null) {
            val newState = state.withStartingPosition(
                state.currentHeadY,
                state.currentShouldersY,
                state.currentHipsY
            ).withLowestBarPosition(barPosition)

            logger.d(TAG, "Starting position set: bar=%.2f m, head=%.2f m, shoulders=%.2f m".format(
                barPosition, state.currentHeadY, state.currentShouldersY))
            return PhaseTransitionResult(
                newState = newState,
                repCompleted = false,
                debugInfo = "Starting position set"
            )
        }

        // Safe: nullability already checked above
        val startingHeadY = state.startingHeadY
        val startingShouldersY = state.startingShouldersY
        val startingBarPosition = (startingHeadY + startingShouldersY) / 2f
        val displacement = barPosition - startingBarPosition

        // Start eccentric phase if moving down more than threshold
        if (displacement > ExerciseThresholds.BackSquat.IDLE_TO_ECCENTRIC_THRESHOLD) {
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
        val startingHeadY = state.startingHeadY
        val startingShouldersY = state.startingShouldersY

        if (barPosition == null || startingHeadY == null || startingShouldersY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val startingBarPosition = (startingHeadY + startingShouldersY) / 2f
        val descent = barPosition - startingBarPosition

        // Track lowest position
        val lowestBar = state.lowestBarPosition
        val updatedState = if (lowestBar == null || barPosition > lowestBar) {
            state.withLowestBarPosition(barPosition)
        } else {
            state
        }

        val sufficientDepth = descent >= criteriaRef.get().minDescentDepth.value
        val depthResult = validateSquatDepth(state)
        val hipsAtDepth = depthResult?.isValid ?: false

        logger.d(TAG, "ECCENTRIC: descent=%.2f m, sufficientDepth=$sufficientDepth, hipsAtDepth=$hipsAtDepth".format(descent))

        if (sufficientDepth && hipsAtDepth) {
            logger.d(TAG, "Phase: ECCENTRIC -> BOTTOM")
            return PhaseTransitionResult(
                newState = updatedState.withCurrentPhase(MovementPhase.BOTTOM),
                repCompleted = false,
                debugInfo = "Reached bottom: %.2f m descent".format(descent),
                depthValidation = depthResult
            )
        }

        return PhaseTransitionResult(
            newState = updatedState,
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

        if (ascent > ExerciseThresholds.BackSquat.BOTTOM_TO_CONCENTRIC_THRESHOLD) {
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
        val startingHeadY = state.startingHeadY
        val startingShouldersY = state.startingShouldersY

        if (barPosition == null || startingHeadY == null || startingShouldersY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val startingBarPosition = (startingHeadY + startingShouldersY) / 2f
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
        val startingHeadY = state.startingHeadY
        val startingShouldersY = state.startingShouldersY

        if (barPosition == null || startingHeadY == null || startingShouldersY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val startingBarPosition = (startingHeadY + startingShouldersY) / 2f
        val descent = barPosition - startingBarPosition

        // Check if starting new rep (descending again)
        if (descent > ExerciseThresholds.BackSquat.IDLE_TO_ECCENTRIC_THRESHOLD) {
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
        if (abs(descent) < ExerciseThresholds.BackSquat.TOP_STABILIZATION_THRESHOLD) {
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
            tolerance = Meters(ExerciseThresholds.BackSquat.DEPTH_TOLERANCE)
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
        calibrationData: CalibrationData
    ): FormValidationResult {
        val issues = mutableListOf<FormIssue>()
        val landmarks = poseResult.landmarks

        // Get body part positions
        val bodyPositions = positionCalculator.getBodyPartPositions(poseResult)

        if (bodyPositions == null) {
            // Cannot validate without body positions
            return FormValidationResult(isValid = true, issues = issues)
        }

        // 1. Check knee alignment (knee valgus/varus)
        val kneeIssue = checkKneeAlignment(landmarks)
        if (kneeIssue != null) {
            issues.add(kneeIssue)
        }

        // 2. Check torso angle (forward lean)
        val torsoIssue = checkTorsoAngle(landmarks, bodyPositions)
        if (torsoIssue != null) {
            issues.add(torsoIssue)
        }

        // 3. Check hip hinge pattern
        val hipIssue = checkHipHinge(bodyPositions)
        if (hipIssue != null) {
            issues.add(hipIssue)
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
     * Check for knee valgus (knees caving inward) or varus (knees bowing outward).
     * Compares knee width to hip width to detect alignment issues.
     */
    private fun checkKneeAlignment(landmarks: List<com.lifthelper.domain.model.PoseKeypoint>): FormIssue? {
        val leftKnee = landmarks.getOrNull(25)
        val rightKnee = landmarks.getOrNull(26)
        val leftHip = landmarks.getOrNull(23)
        val rightHip = landmarks.getOrNull(24)

        if (leftKnee == null || rightKnee == null || leftHip == null || rightHip == null) {
            return null
        }

        if (leftKnee.visibility < 0.5f || rightKnee.visibility < 0.5f ||
            leftHip.visibility < 0.5f || rightHip.visibility < 0.5f) {
            return null
        }

        val kneeWidth = kotlin.math.abs(rightKnee.x - leftKnee.x)
        val hipWidth = kotlin.math.abs(rightHip.x - leftHip.x)

        // Knees should be approximately as wide as hips (±20%)
        val kneeToHipRatio = kneeWidth / hipWidth

        return when {
            kneeToHipRatio < 0.70f -> FormIssue(
                severity = Severity.CRITICAL,
                message = "Knees caving inward (valgus collapse)",
                suggestion = "Push knees outward to track over toes"
            )
            kneeToHipRatio > 1.30f -> FormIssue(
                severity = Severity.WARNING,
                message = "Knees bowing outward excessively",
                suggestion = "Allow knees to track naturally over toes"
            )
            else -> null
        }
    }

    /**
     * Check torso angle to detect excessive forward lean.
     * Excessive forward lean increases lower back stress.
     */
    private fun checkTorsoAngle(
        landmarks: List<com.lifthelper.domain.model.PoseKeypoint>,
        bodyPositions: BodyPartPositions
    ): FormIssue? {
        val shoulders = bodyPositions.shouldersY ?: return null
        val hips = bodyPositions.hipsY ?: return null
        val shouldersX = bodyPositions.hipsX ?: return null  // Use average X
        val hipsX = bodyPositions.hipsX ?: return null

        // Calculate torso angle from vertical
        val torsoVerticalDistance = kotlin.math.abs(shoulders - hips)
        val torsoHorizontalDistance = kotlin.math.abs(shouldersX - hipsX)

        if (torsoVerticalDistance < 0.01f) return null  // Avoid division by zero

        // Angle from vertical in degrees (0° = perfectly upright)
        val angleFromVertical = Math.toDegrees(
            kotlin.math.atan2(torsoHorizontalDistance, torsoVerticalDistance).toDouble()
        ).toFloat()

        return when {
            angleFromVertical > 50f -> FormIssue(
                severity = Severity.CRITICAL,
                message = "Excessive forward lean (${angleFromVertical.toInt()}° from vertical)",
                suggestion = "Keep chest up and sit back into the squat"
            )
            angleFromVertical > 35f -> FormIssue(
                severity = Severity.WARNING,
                message = "Moderate forward lean (${angleFromVertical.toInt()}° from vertical)",
                suggestion = "Try to maintain more upright torso position"
            )
            else -> null
        }
    }

    /**
     * Check hip hinge pattern - hips should move back and down together.
     */
    private fun checkHipHinge(bodyPositions: BodyPartPositions): FormIssue? {
        val hips = bodyPositions.hipsY ?: return null
        val knees = bodyPositions.kneesY ?: return null

        // In a good hip hinge, hips should descend at least as much as knees
        // If knees move forward too much relative to hips, it's more knee-dominant
        val hipKneeDifference = kotlin.math.abs(hips - knees)

        // This is a simplified check; full implementation would track movement over time
        return if (hipKneeDifference < 0.05f) {
            FormIssue(
                severity = Severity.INFO,
                message = "Limited hip hinge pattern",
                suggestion = "Focus on pushing hips back as you descend"
            )
        } else {
            null
        }
    }

    /**
     * Check foot stability - detect heel lift which compromises stability.
     */
    private fun checkFootStability(landmarks: List<com.lifthelper.domain.model.PoseKeypoint>): FormIssue? {
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
                suggestion = "Keep weight on heels throughout the movement"
            )
        } else {
            null
        }
    }

    override fun getRequiredLandmarks(): List<Int> = criteriaRef.get().targetBodyParts

    override fun getCriteria(): ExerciseCriteria = criteriaRef.get()
}
