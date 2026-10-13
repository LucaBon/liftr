package com.liftr.domain.exercise.strategy

import com.liftr.domain.calculator.CoordinateCalculator
import com.liftr.domain.exercise.ExerciseCriteria
import com.liftr.domain.exercise.ExerciseMetrics
import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseStrategy
import com.liftr.domain.exercise.ExerciseThresholds
import com.liftr.domain.exercise.FormIssue
import com.liftr.domain.exercise.FormValidationResult
import com.liftr.domain.exercise.PhaseTransitionResult
import com.liftr.domain.exercise.Severity
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.VelocityData
import com.liftr.domain.value.Joules
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Watts
import com.liftr.domain.vision.IPositionCalculator
import com.liftr.domain.model.MovementPhase
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.math.abs

/**
 * Strategy for bench press exercise.
 *
 * Tracks wrist position as the primary bar indicator. Phase detection uses
 * wrist Y descent/ascent. Lockout requires elbow extension angle > 150 degrees.
 *
 * Phase order: IDLE -> ECCENTRIC -> BOTTOM -> CONCENTRIC -> TOP (same as squat)
 */
class BenchPressStrategy @Inject constructor(
    private val positionCalculator: IPositionCalculator,
    private val logger: Logger
) : ExerciseStrategy {

    override val exerciseType = ExerciseType.BENCH_PRESS

    private val coordinateCalculator = CoordinateCalculator()

    private val criteriaRef = AtomicReference(ExerciseCriteria(
        minDescentDepth = ExerciseThresholds.BenchPress.MIN_DESCENT_DEPTH,
        returnTolerance = ExerciseThresholds.BenchPress.RETURN_TOLERANCE,
        minVelocity = ExerciseThresholds.BenchPress.MIN_VELOCITY,
        targetBodyParts = listOf(
            PoseResult.LEFT_SHOULDER,
            PoseResult.RIGHT_SHOULDER,
            PoseResult.LEFT_ELBOW,
            PoseResult.RIGHT_ELBOW,
            PoseResult.LEFT_WRIST,
            PoseResult.RIGHT_WRIST,
            PoseResult.LEFT_HIP,
            PoseResult.RIGHT_HIP
        )
    ))

    companion object {
        private const val TAG = "BenchPressStrategy"
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
        val wristsY = bodyPositions.wristsY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val wristsX = bodyPositions.wristsX?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val elbowsY = bodyPositions.elbowsY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }

        // Calculate elbow angle from raw landmarks
        val elbowAngle = calculateElbowAngle(poseResult)

        // Update state with current positions
        val updatedState = currentState
            .withCurrentPositions(
                headY = bodyPositions.headY?.let { positionCalculator.pixelsToMeters(it, poseResult, calibrationData) },
                shouldersY = bodyPositions.shouldersY?.let { positionCalculator.pixelsToMeters(it, poseResult, calibrationData) },
                hipsY = bodyPositions.hipsY?.let { positionCalculator.pixelsToMeters(it, poseResult, calibrationData) },
                kneesY = null
            )
            .withWristPositions(wristsY, wristsX)
            .withElbowData(elbowsY, elbowAngle)

        return when (updatedState.currentPhase) {
            MovementPhase.IDLE -> detectIdleTransition(updatedState, wristsY)
            MovementPhase.ECCENTRIC -> detectEccentricTransition(updatedState, wristsY)
            MovementPhase.BOTTOM -> detectBottomTransition(updatedState, wristsY)
            MovementPhase.CONCENTRIC -> detectConcentricTransition(updatedState, wristsY, elbowAngle)
            MovementPhase.TOP -> detectTopTransition(updatedState, wristsY)
        }
    }

    /**
     * Calculate elbow angle using shoulder, elbow, and wrist landmarks.
     * Returns angle in degrees (180 = fully extended, 90 = bent at right angle).
     */
    private fun calculateElbowAngle(poseResult: PoseResult): Float? {
        val landmarks = poseResult.landmarks
        val leftShoulder = landmarks.getOrNull(PoseResult.LEFT_SHOULDER)
        val rightShoulder = landmarks.getOrNull(PoseResult.RIGHT_SHOULDER)
        val leftElbow = landmarks.getOrNull(PoseResult.LEFT_ELBOW)
        val rightElbow = landmarks.getOrNull(PoseResult.RIGHT_ELBOW)
        val leftWrist = landmarks.getOrNull(PoseResult.LEFT_WRIST)
        val rightWrist = landmarks.getOrNull(PoseResult.RIGHT_WRIST)

        if (leftShoulder == null || rightShoulder == null ||
            leftElbow == null || rightElbow == null ||
            leftWrist == null || rightWrist == null) return null

        val minVisibility = 0.5f
        if (leftShoulder.visibility < minVisibility || rightShoulder.visibility < minVisibility ||
            leftElbow.visibility < minVisibility || rightElbow.visibility < minVisibility ||
            leftWrist.visibility < minVisibility || rightWrist.visibility < minVisibility) return null

        // Average left and right sides
        val shoulderX = (leftShoulder.x + rightShoulder.x) / 2f
        val shoulderY = (leftShoulder.y + rightShoulder.y) / 2f
        val elbowX = (leftElbow.x + rightElbow.x) / 2f
        val elbowY = (leftElbow.y + rightElbow.y) / 2f
        val wristX = (leftWrist.x + rightWrist.x) / 2f
        val wristY = (leftWrist.y + rightWrist.y) / 2f

        return coordinateCalculator.angleBetweenPoints(
            ax = shoulderX, ay = shoulderY,
            bx = elbowX, by = elbowY,
            cx = wristX, cy = wristY
        )
    }

    private fun detectIdleTransition(state: ExercisePhaseState, wristsY: Float?): PhaseTransitionResult {
        if (wristsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "No wrist position"
            )
        }

        // Set starting wrist position when we have stable data
        if (state.startingWristsY == null) {
            val newState = state
                .withStartingWristsY(wristsY)
                .withLowestWristsY(wristsY)
            logger.d(TAG, "Starting wrist position set: %.2f m".format(wristsY))
            return PhaseTransitionResult(
                newState = newState,
                repCompleted = false,
                debugInfo = "Starting position set"
            )
        }

        val startingWristsY = state.startingWristsY
        val displacement = wristsY - startingWristsY

        // Bar descending (wrist Y increases as bar lowers toward chest in normalized coords)
        if (displacement > ExerciseThresholds.BenchPress.IDLE_TO_ECCENTRIC_THRESHOLD) {
            logger.d(TAG, "Phase: IDLE -> ECCENTRIC (displacement: %.2f m)".format(displacement))
            val newState = state
                .withCurrentPhase(MovementPhase.ECCENTRIC)
                .withLowestWristsY(wristsY)
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

    private fun detectEccentricTransition(state: ExercisePhaseState, wristsY: Float?): PhaseTransitionResult {
        val startingWristsY = state.startingWristsY

        if (wristsY == null || startingWristsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val descent = wristsY - startingWristsY

        // Track lowest wrist position (bar at chest)
        val lowestWrists = state.lowestWristsY
        val updatedState = if (lowestWrists == null || wristsY > lowestWrists) {
            state.withLowestWristsY(wristsY)
        } else {
            state
        }

        val sufficientDepth = descent >= criteriaRef.get().minDescentDepth.value

        logger.d(TAG, "ECCENTRIC: descent=%.2f m, sufficientDepth=$sufficientDepth".format(descent))

        if (sufficientDepth) {
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

    private fun detectBottomTransition(state: ExercisePhaseState, wristsY: Float?): PhaseTransitionResult {
        val lowestWrists = state.lowestWristsY

        if (wristsY == null || lowestWrists == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        // Check if starting to press up (wrists moving up from lowest point)
        val ascent = lowestWrists - wristsY

        logger.d(TAG, "BOTTOM: ascent=%.2f m".format(ascent))

        if (ascent > ExerciseThresholds.BenchPress.BOTTOM_TO_CONCENTRIC_THRESHOLD) {
            logger.d(TAG, "Phase: BOTTOM -> CONCENTRIC")
            return PhaseTransitionResult(
                newState = state.withCurrentPhase(MovementPhase.CONCENTRIC),
                repCompleted = false,
                debugInfo = "Press started: %.2f m".format(ascent)
            )
        }

        return PhaseTransitionResult(
            newState = state,
            repCompleted = false,
            debugInfo = "At bottom (chest)"
        )
    }

    private fun detectConcentricTransition(
        state: ExercisePhaseState,
        wristsY: Float?,
        elbowAngle: Float?
    ): PhaseTransitionResult {
        val startingWristsY = state.startingWristsY

        if (wristsY == null || startingWristsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val returnDistance = abs(wristsY - startingWristsY)

        // Lockout requires returning near start AND sufficient elbow extension
        val nearStart = returnDistance <= criteriaRef.get().returnTolerance.value
        val elbowsExtended = elbowAngle == null || elbowAngle >= ExerciseThresholds.BenchPress.LOCKOUT_MIN_ANGLE

        logger.d(TAG, "CONCENTRIC: returnDist=%.2f m, elbowAngle=${elbowAngle?.let { "%.1f°".format(it) } ?: "N/A"}, nearStart=$nearStart, elbowsExtended=$elbowsExtended".format(returnDistance))

        if (nearStart && elbowsExtended) {
            logger.d(TAG, "Phase: CONCENTRIC -> TOP (rep completed)")
            return PhaseTransitionResult(
                newState = state.withCurrentPhase(MovementPhase.TOP),
                repCompleted = true,
                debugInfo = "Rep completed (lockout)"
            )
        }

        return PhaseTransitionResult(
            newState = state,
            repCompleted = false,
            debugInfo = "Pressing: %.2f m from start".format(returnDistance)
        )
    }

    private fun detectTopTransition(state: ExercisePhaseState, wristsY: Float?): PhaseTransitionResult {
        val startingWristsY = state.startingWristsY

        if (wristsY == null || startingWristsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val descent = wristsY - startingWristsY

        // Check if starting new rep
        if (descent > ExerciseThresholds.BenchPress.IDLE_TO_ECCENTRIC_THRESHOLD) {
            logger.d(TAG, "Phase: TOP -> ECCENTRIC (new rep)")
            return PhaseTransitionResult(
                newState = state
                    .withCurrentPhase(MovementPhase.ECCENTRIC)
                    .withLowestWristsY(wristsY),
                repCompleted = false,
                debugInfo = "New rep started"
            )
        }

        // Check if stabilized at top
        if (abs(descent) < ExerciseThresholds.BenchPress.TOP_STABILIZATION_THRESHOLD) {
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
        val landmarks = poseResult.landmarks

        // Elbow flare and left/right press symmetry require a view along the bar
        // (phone past the lifter's head). The app films bench laterally, so those
        // checks are intentionally absent.
        checkBarDrift(landmarks, calibrationData, poseResult)?.let { issues.add(it) }
        checkIncompleteLockout(poseResult)?.let { issues.add(it) }

        return FormValidationResult(
            isValid = issues.none { it.severity == Severity.CRITICAL },
            issues = issues
        )
    }

    /**
     * Check horizontal bar drift from starting position.
     */
    private fun checkBarDrift(
        landmarks: List<com.liftr.domain.model.PoseKeypoint>,
        calibrationData: CalibrationData,
        poseResult: PoseResult
    ): FormIssue? {
        val leftWrist = landmarks.getOrNull(PoseResult.LEFT_WRIST) ?: return null
        val rightWrist = landmarks.getOrNull(PoseResult.RIGHT_WRIST) ?: return null

        if (leftWrist.visibility < 0.5f || rightWrist.visibility < 0.5f) return null

        val wristsX = (leftWrist.x + rightWrist.x) / 2f
        val wristsXMeters = positionCalculator.pixelsToMeters(wristsX, poseResult, calibrationData)

        // Compare with shoulders X as reference (bar should be roughly above shoulders)
        val leftShoulder = landmarks.getOrNull(PoseResult.LEFT_SHOULDER) ?: return null
        val rightShoulder = landmarks.getOrNull(PoseResult.RIGHT_SHOULDER) ?: return null

        if (leftShoulder.visibility < 0.5f || rightShoulder.visibility < 0.5f) return null

        val shouldersX = (leftShoulder.x + rightShoulder.x) / 2f
        val shouldersXMeters = positionCalculator.pixelsToMeters(shouldersX, poseResult, calibrationData)

        val drift = abs(wristsXMeters - shouldersXMeters)

        return if (drift > ExerciseThresholds.BenchPress.BAR_DRIFT_MAX) {
            FormIssue(
                severity = Severity.WARNING,
                message = "Bar drifting horizontally (%.0f cm)".format(drift * 100),
                suggestion = "Keep the bar path more vertical"
            )
        } else null
    }

    /**
     * Check for incomplete lockout at top of press.
     */
    private fun checkIncompleteLockout(poseResult: PoseResult): FormIssue? {
        val elbowAngle = calculateElbowAngle(poseResult) ?: return null

        return if (elbowAngle < ExerciseThresholds.BenchPress.LOCKOUT_MIN_ANGLE) {
            FormIssue(
                severity = Severity.INFO,
                message = "Incomplete lockout (elbow angle: %.0f°)".format(elbowAngle),
                suggestion = "Fully extend your arms at the top"
            )
        } else null
    }

    override fun getRequiredLandmarks(): List<Int> = criteriaRef.get().targetBodyParts

    override fun getCriteria(): ExerciseCriteria = criteriaRef.get()
}
