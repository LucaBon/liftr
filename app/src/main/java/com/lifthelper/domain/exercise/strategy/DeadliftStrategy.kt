package com.lifthelper.domain.exercise.strategy

import com.lifthelper.domain.calculator.CoordinateCalculator
import com.lifthelper.domain.exercise.ExerciseCriteria
import com.lifthelper.domain.exercise.ExerciseMetrics
import com.lifthelper.domain.exercise.ExercisePhaseState
import com.lifthelper.domain.exercise.ExerciseStrategy
import com.lifthelper.domain.exercise.ExerciseThresholds
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
import com.lifthelper.domain.value.Watts
import com.lifthelper.domain.vision.IPositionCalculator
import com.lifthelper.vision.tracking.MovementPhase
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.math.abs

/**
 * Strategy for conventional deadlift exercise.
 *
 * The deadlift is a concentric-first movement (bar starts on the floor).
 * Phase order: IDLE -> CONCENTRIC -> TOP -> ECCENTRIC -> IDLE
 *
 * Primary tracking point: hips Y (hip hinge is the core movement).
 * Lockout requires full hip and knee extension.
 * Rep is counted at CONCENTRIC -> TOP transition (lockout).
 */
class DeadliftStrategy @Inject constructor(
    private val positionCalculator: IPositionCalculator,
    private val logger: Logger
) : ExerciseStrategy {

    override val exerciseType = ExerciseType.DEADLIFT

    private val coordinateCalculator = CoordinateCalculator()

    private val criteriaRef = AtomicReference(ExerciseCriteria(
        minDescentDepth = ExerciseThresholds.Deadlift.MIN_LIFT_HEIGHT,
        returnTolerance = ExerciseThresholds.Deadlift.RETURN_TOLERANCE,
        minVelocity = ExerciseThresholds.Deadlift.MIN_VELOCITY,
        targetBodyParts = listOf(
            PoseResult.NOSE,
            PoseResult.LEFT_SHOULDER,
            PoseResult.RIGHT_SHOULDER,
            PoseResult.LEFT_HIP,
            PoseResult.RIGHT_HIP,
            PoseResult.LEFT_KNEE,
            PoseResult.RIGHT_KNEE,
            PoseResult.LEFT_ANKLE,
            PoseResult.RIGHT_ANKLE
        )
    ))

    companion object {
        private const val TAG = "DeadliftStrategy"
    }

    override fun detectPhase(
        poseResult: PoseResult,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState
    ): PhaseTransitionResult {
        val bodyPositions = positionCalculator.getBodyPartPositions(poseResult)
        if (bodyPositions == null) {
            return PhaseTransitionResult(
                newState = currentState,
                repCompleted = false,
                debugInfo = "No body positions detected"
            )
        }

        // Convert to meters
        val currentHipsY = bodyPositions.hipsY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentShouldersY = bodyPositions.shouldersY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentKneesY = bodyPositions.kneesY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentAnklesY = bodyPositions.anklesY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentHipsX = bodyPositions.hipsX?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentShouldersX = bodyPositions.shouldersX?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentAnklesX = bodyPositions.anklesX?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }

        // Update state with current positions
        val updatedState = currentState
            .withCurrentPositions(
                headY = bodyPositions.headY?.let { positionCalculator.pixelsToMeters(it, poseResult, calibrationData) },
                shouldersY = currentShouldersY,
                hipsY = currentHipsY,
                kneesY = currentKneesY,
                hipsX = currentHipsX,
                kneesX = bodyPositions.kneesX?.let { positionCalculator.pixelsToMeters(it, poseResult, calibrationData) }
            )
            .withAnklePositions(currentAnklesY, currentAnklesX)
            .withShouldersX(currentShouldersX)

        // Deadlift phase order: IDLE -> CONCENTRIC -> TOP -> ECCENTRIC -> IDLE
        return when (updatedState.currentPhase) {
            MovementPhase.IDLE -> detectIdleToConcentric(updatedState, currentHipsY, currentShouldersX, currentHipsX)
            MovementPhase.CONCENTRIC -> detectConcentricToTop(updatedState, currentHipsY, poseResult)
            MovementPhase.TOP -> detectTopToEccentric(updatedState, currentHipsY)
            MovementPhase.ECCENTRIC -> detectEccentricToIdle(updatedState, currentHipsY)
            MovementPhase.BOTTOM -> {
                // Deadlift doesn't use BOTTOM phase — skip to IDLE
                logger.d(TAG, "Phase: BOTTOM -> IDLE (unused phase)")
                PhaseTransitionResult(
                    newState = updatedState.withCurrentPhase(MovementPhase.IDLE),
                    repCompleted = false,
                    debugInfo = "BOTTOM unused for deadlift"
                )
            }
        }
    }

    private fun detectIdleToConcentric(
        state: ExercisePhaseState,
        hipsY: Float?,
        shouldersX: Float?,
        hipsX: Float?
    ): PhaseTransitionResult {
        if (hipsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "No hip position"
            )
        }

        // Set starting position (bent-over position at floor)
        if (state.startingHipsY == null) {
            // Record initial torso angle for back rounding detection
            val initialTorsoAngle = if (shouldersX != null && hipsX != null &&
                state.currentShouldersY != null) {
                coordinateCalculator.torsoAngle(
                    shoulderX = shouldersX,
                    shoulderY = state.currentShouldersY,
                    hipX = hipsX,
                    hipY = hipsY
                )
            } else null

            val newState = state
                .withStartingPosition(state.currentHeadY, state.currentShouldersY, hipsY)
                .withHighestHipsY(hipsY)
                .withStartingTorsoAngle(initialTorsoAngle)

            logger.d(TAG, "Starting position set: hips=%.2f m, torsoAngle=${initialTorsoAngle?.let { "%.1f°".format(it) } ?: "N/A"}".format(hipsY))
            return PhaseTransitionResult(
                newState = newState,
                repCompleted = false,
                debugInfo = "Starting position set"
            )
        }

        // Re-baseline while still idle whenever the hips settle lower than the
        // recorded start (larger Y = lower). A deadlift begins from the bottom,
        // so the true setup is the lowest hip position seen before the pull.
        // Without this, recording that starts while the lifter is standing locks
        // the baseline at standing height — the hips can then never rise far
        // enough above it to arm the pull and the whole set counts zero reps.
        if (hipsY > state.startingHipsY) {
            val rebaselinedTorsoAngle = if (state.currentShouldersX != null &&
                state.currentHipsX != null && state.currentShouldersY != null) {
                coordinateCalculator.torsoAngle(
                    shoulderX = state.currentShouldersX,
                    shoulderY = state.currentShouldersY,
                    hipX = state.currentHipsX,
                    hipY = hipsY
                )
            } else state.startingTorsoAngle

            logger.d(TAG, "Re-baselined start: hips %.2f -> %.2f m".format(state.startingHipsY, hipsY))
            return PhaseTransitionResult(
                newState = state
                    .withStartingPosition(state.currentHeadY, state.currentShouldersY, hipsY)
                    .withHighestHipsY(hipsY)
                    .withStartingTorsoAngle(rebaselinedTorsoAngle),
                repCompleted = false,
                debugInfo = "Setup position lowered"
            )
        }

        val startingHipsY = state.startingHipsY

        // Deadlift starts from the floor — hips move UP (Y decreases in our coord system
        // where larger Y = lower). So displacement = startingHipsY - hipsY.
        val upwardDisplacement = startingHipsY - hipsY

        if (upwardDisplacement > ExerciseThresholds.Deadlift.IDLE_TO_CONCENTRIC_THRESHOLD) {
            // Record torso angle at the start of the pull
            val torsoAngle = if (state.currentShouldersX != null && state.currentHipsX != null &&
                state.currentShouldersY != null) {
                coordinateCalculator.torsoAngle(
                    shoulderX = state.currentShouldersX,
                    shoulderY = state.currentShouldersY,
                    hipX = state.currentHipsX,
                    hipY = hipsY
                )
            } else state.startingTorsoAngle

            logger.d(TAG, "Phase: IDLE -> CONCENTRIC (upward: %.2f m)".format(upwardDisplacement))
            return PhaseTransitionResult(
                newState = state
                    .withCurrentPhase(MovementPhase.CONCENTRIC)
                    .withHighestHipsY(hipsY)
                    .withStartingTorsoAngle(torsoAngle),
                repCompleted = false,
                debugInfo = "Pull started: %.2f m".format(upwardDisplacement)
            )
        }

        return PhaseTransitionResult(
            newState = state,
            repCompleted = false,
            debugInfo = "Waiting for pull"
        )
    }

    private fun detectConcentricToTop(
        state: ExercisePhaseState,
        hipsY: Float?,
        poseResult: PoseResult
    ): PhaseTransitionResult {
        val startingHipsY = state.startingHipsY

        if (hipsY == null || startingHipsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val liftHeight = startingHipsY - hipsY

        // Track highest hip position (lockout)
        val highestHips = state.highestHipsY
        val updatedState = if (highestHips == null || hipsY < highestHips) {
            state.withHighestHipsY(hipsY)
        } else {
            state
        }

        val sufficientHeight = liftHeight >= criteriaRef.get().minDescentDepth.value

        // Check lockout: hip and knee extension
        val lockoutAchieved = if (sufficientHeight) {
            checkLockout(poseResult)
        } else false

        logger.d(TAG, "CONCENTRIC: liftHeight=%.2f m, sufficient=$sufficientHeight, lockout=$lockoutAchieved".format(liftHeight))

        if (sufficientHeight && lockoutAchieved) {
            logger.d(TAG, "Phase: CONCENTRIC -> TOP (rep completed)")
            return PhaseTransitionResult(
                newState = updatedState.withCurrentPhase(MovementPhase.TOP),
                repCompleted = true,
                debugInfo = "Rep completed (lockout): %.2f m pull".format(liftHeight)
            )
        }

        return PhaseTransitionResult(
            newState = updatedState,
            repCompleted = false,
            debugInfo = "Pulling: %.2f m".format(liftHeight)
        )
    }

    private fun detectTopToEccentric(state: ExercisePhaseState, hipsY: Float?): PhaseTransitionResult {
        val highestHipsY = state.highestHipsY

        if (hipsY == null || highestHipsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        // Hips moving down from lockout (Y increases)
        val descent = hipsY - highestHipsY

        if (descent > ExerciseThresholds.Deadlift.TOP_TO_ECCENTRIC_THRESHOLD) {
            logger.d(TAG, "Phase: TOP -> ECCENTRIC (descent: %.2f m)".format(descent))
            return PhaseTransitionResult(
                newState = state.withCurrentPhase(MovementPhase.ECCENTRIC),
                repCompleted = false,
                debugInfo = "Lowering started: %.2f m".format(descent)
            )
        }

        // Check if stabilized at top — could transition directly to IDLE for next rep
        if (abs(descent) < ExerciseThresholds.Deadlift.TOP_STABILIZATION_THRESHOLD) {
            // Stay at TOP briefly
        }

        return PhaseTransitionResult(
            newState = state,
            repCompleted = false,
            debugInfo = "At lockout"
        )
    }

    private fun detectEccentricToIdle(state: ExercisePhaseState, hipsY: Float?): PhaseTransitionResult {
        val startingHipsY = state.startingHipsY

        if (hipsY == null || startingHipsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val returnDistance = abs(hipsY - startingHipsY)

        logger.d(TAG, "ECCENTRIC: returnDist=%.2f m".format(returnDistance))

        // Bar returned to floor (hips back near starting position)
        if (returnDistance <= criteriaRef.get().returnTolerance.value) {
            logger.d(TAG, "Phase: ECCENTRIC -> IDLE (bar on floor)")
            return PhaseTransitionResult(
                newState = state
                    .withCurrentPhase(MovementPhase.IDLE)
                    .withHighestHipsY(null),
                repCompleted = false,
                debugInfo = "Bar on floor"
            )
        }

        return PhaseTransitionResult(
            newState = state,
            repCompleted = false,
            debugInfo = "Lowering: %.2f m from start".format(returnDistance)
        )
    }

    /**
     * Check if full lockout is achieved.
     * Requires both hip extension and knee extension above threshold angles.
     */
    private fun checkLockout(poseResult: PoseResult): Boolean {
        val hipAngle = calculateHipAngle(poseResult)
        val kneeAngle = calculateKneeAngle(poseResult)

        if (hipAngle == null && kneeAngle == null) {
            // If we can't compute either angle, allow lockout based on height alone
            return true
        }

        val hipOk = hipAngle == null || hipAngle >= ExerciseThresholds.Deadlift.LOCKOUT_HIP_ANGLE
        val kneeOk = kneeAngle == null || kneeAngle >= ExerciseThresholds.Deadlift.LOCKOUT_KNEE_ANGLE

        logger.d(TAG, "Lockout check: hipAngle=${hipAngle?.let { "%.1f°".format(it) } ?: "N/A"}, " +
            "kneeAngle=${kneeAngle?.let { "%.1f°".format(it) } ?: "N/A"}, hipOk=$hipOk, kneeOk=$kneeOk")

        return hipOk && kneeOk
    }

    /**
     * Calculate hip angle: angle at hip formed by shoulder-hip-knee.
     * 180° = fully extended (standing tall), smaller = more bent.
     */
    private fun calculateHipAngle(poseResult: PoseResult): Float? {
        val landmarks = poseResult.landmarks
        val leftShoulder = landmarks.getOrNull(PoseResult.LEFT_SHOULDER)
        val rightShoulder = landmarks.getOrNull(PoseResult.RIGHT_SHOULDER)
        val leftHip = landmarks.getOrNull(PoseResult.LEFT_HIP)
        val rightHip = landmarks.getOrNull(PoseResult.RIGHT_HIP)
        val leftKnee = landmarks.getOrNull(PoseResult.LEFT_KNEE)
        val rightKnee = landmarks.getOrNull(PoseResult.RIGHT_KNEE)

        val minVis = 0.5f
        if (leftShoulder == null || rightShoulder == null ||
            leftHip == null || rightHip == null ||
            leftKnee == null || rightKnee == null) return null
        if (leftShoulder.visibility < minVis || rightShoulder.visibility < minVis ||
            leftHip.visibility < minVis || rightHip.visibility < minVis ||
            leftKnee.visibility < minVis || rightKnee.visibility < minVis) return null

        val shoulderX = (leftShoulder.x + rightShoulder.x) / 2f
        val shoulderY = (leftShoulder.y + rightShoulder.y) / 2f
        val hipX = (leftHip.x + rightHip.x) / 2f
        val hipY = (leftHip.y + rightHip.y) / 2f
        val kneeX = (leftKnee.x + rightKnee.x) / 2f
        val kneeY = (leftKnee.y + rightKnee.y) / 2f

        return coordinateCalculator.angleBetweenPoints(
            ax = shoulderX, ay = shoulderY,
            bx = hipX, by = hipY,
            cx = kneeX, cy = kneeY
        )
    }

    /**
     * Calculate knee angle: angle at knee formed by hip-knee-ankle.
     * 180° = fully extended, smaller = more bent.
     */
    private fun calculateKneeAngle(poseResult: PoseResult): Float? {
        val landmarks = poseResult.landmarks
        val leftHip = landmarks.getOrNull(PoseResult.LEFT_HIP)
        val rightHip = landmarks.getOrNull(PoseResult.RIGHT_HIP)
        val leftKnee = landmarks.getOrNull(PoseResult.LEFT_KNEE)
        val rightKnee = landmarks.getOrNull(PoseResult.RIGHT_KNEE)
        val leftAnkle = landmarks.getOrNull(PoseResult.LEFT_ANKLE)
        val rightAnkle = landmarks.getOrNull(PoseResult.RIGHT_ANKLE)

        val minVis = 0.5f
        if (leftHip == null || rightHip == null ||
            leftKnee == null || rightKnee == null ||
            leftAnkle == null || rightAnkle == null) return null
        if (leftHip.visibility < minVis || rightHip.visibility < minVis ||
            leftKnee.visibility < minVis || rightKnee.visibility < minVis ||
            leftAnkle.visibility < minVis || rightAnkle.visibility < minVis) return null

        val hipX = (leftHip.x + rightHip.x) / 2f
        val hipY = (leftHip.y + rightHip.y) / 2f
        val kneeX = (leftKnee.x + rightKnee.x) / 2f
        val kneeY = (leftKnee.y + rightKnee.y) / 2f
        val ankleX = (leftAnkle.x + rightAnkle.x) / 2f
        val ankleY = (leftAnkle.y + rightAnkle.y) / 2f

        return coordinateCalculator.angleBetweenPoints(
            ax = hipX, ay = hipY,
            bx = kneeX, by = kneeY,
            cx = ankleX, cy = ankleY
        )
    }

    override fun calculateMetrics(
        poseResult: PoseResult,
        velocityData: VelocityData,
        weight: Kilograms
    ): ExerciseMetrics {
        val peakVelocity = velocityData.instantaneous.abs()
        val force = weight.value * 9.81f
        val avgPower = Watts(force * velocityData.average.abs().value)
        val peakPower = Watts(force * peakVelocity.value)
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

        checkBackRounding(poseResult, currentState?.startingTorsoAngle)?.let { issues.add(it) }
        checkLockoutCompleteness(poseResult)?.let { issues.add(it) }
        checkBarDrift(poseResult, calibrationData)?.let { issues.add(it) }

        return FormValidationResult(
            isValid = issues.none { it.severity == Severity.CRITICAL },
            issues = issues
        )
    }

    /**
     * Check for back rounding during the pull.
     * Compares current torso angle with starting angle — if it increases by more
     * than the threshold, the lifter's back is rounding under load.
     */
    private fun checkBackRounding(
        poseResult: PoseResult,
        startingTorsoAngle: Float?
    ): FormIssue? {
        // Without the setup angle there is no baseline to compare against, and
        // absolute torso lean says nothing about rounding: a correct conventional
        // deadlift sets up at 45-70° from vertical, so any absolute threshold in
        // that range fires on every well-executed rep.
        if (startingTorsoAngle == null) return null

        val landmarks = poseResult.landmarks

        val leftShoulder = landmarks.getOrNull(PoseResult.LEFT_SHOULDER)
        val rightShoulder = landmarks.getOrNull(PoseResult.RIGHT_SHOULDER)
        val leftHip = landmarks.getOrNull(PoseResult.LEFT_HIP)
        val rightHip = landmarks.getOrNull(PoseResult.RIGHT_HIP)

        val minVis = 0.5f
        if (leftShoulder == null || rightShoulder == null ||
            leftHip == null || rightHip == null) return null
        if (leftShoulder.visibility < minVis || rightShoulder.visibility < minVis ||
            leftHip.visibility < minVis || rightHip.visibility < minVis) return null

        val shoulderX = (leftShoulder.x + rightShoulder.x) / 2f
        val shoulderY = (leftShoulder.y + rightShoulder.y) / 2f
        val hipX = (leftHip.x + rightHip.x) / 2f
        val hipY = (leftHip.y + rightHip.y) / 2f

        val currentAngle = coordinateCalculator.torsoAngle(shoulderX, shoulderY, hipX, hipY)

        // Rounding shows up as the torso tipping further forward than it was at
        // setup while the bar is moving — the hips rising faster than the chest.
        // Both angles come from landmark coordinates whose X and Y share one
        // scale factor, so they are directly comparable.
        val angleIncrease = currentAngle - startingTorsoAngle
        val threshold = ExerciseThresholds.Deadlift.BACK_ROUNDING_THRESHOLD

        return when {
            angleIncrease > threshold -> FormIssue(
                severity = Severity.CRITICAL,
                message = "Back rounding detected (${angleIncrease.toInt()}° more than setup)",
                suggestion = "Keep chest up and back flat"
            )
            angleIncrease > threshold * 0.6f -> FormIssue(
                severity = Severity.WARNING,
                message = "Torso tipping forward under load (${angleIncrease.toInt()}°)",
                suggestion = "Engage lats and keep chest proud"
            )
            else -> null
        }
    }

    /**
     * Check lockout completeness — hips and knees should be fully extended at top.
     */
    private fun checkLockoutCompleteness(poseResult: PoseResult): FormIssue? {
        val hipAngle = calculateHipAngle(poseResult) ?: return null
        val kneeAngle = calculateKneeAngle(poseResult) ?: return null

        return if (hipAngle < ExerciseThresholds.Deadlift.LOCKOUT_HIP_ANGLE ||
            kneeAngle < ExerciseThresholds.Deadlift.LOCKOUT_KNEE_ANGLE) {
            FormIssue(
                severity = Severity.INFO,
                message = "Lockout incomplete (hip: %.0f°, knee: %.0f°)".format(hipAngle, kneeAngle),
                suggestion = "Drive hips through and stand tall at the top"
            )
        } else null
    }

    /**
     * Check if bar is drifting away from the body.
     * Bar should stay close to the shins/thighs — drift forward increases lower back load.
     */
    private fun checkBarDrift(
        poseResult: PoseResult,
        calibrationData: CalibrationData
    ): FormIssue? {
        val bodyPositions = positionCalculator.getBodyPartPositions(poseResult) ?: return null

        val hipsX = bodyPositions.hipsX ?: return null
        val anklesX = bodyPositions.anklesX ?: return null

        val hipsXMeters = positionCalculator.pixelsToMeters(hipsX, poseResult, calibrationData)
        val anklesXMeters = positionCalculator.pixelsToMeters(anklesX, poseResult, calibrationData)

        val drift = abs(hipsXMeters - anklesXMeters)

        return if (drift > ExerciseThresholds.Deadlift.BAR_DRIFT_MAX) {
            FormIssue(
                severity = Severity.WARNING,
                message = "Bar drifting away from body (%.0f cm)".format(drift * 100),
                suggestion = "Keep the bar close to your shins and thighs"
            )
        } else null
    }

    override fun getRequiredLandmarks(): List<Int> = criteriaRef.get().targetBodyParts

    override fun getCriteria(): ExerciseCriteria = criteriaRef.get()
}
