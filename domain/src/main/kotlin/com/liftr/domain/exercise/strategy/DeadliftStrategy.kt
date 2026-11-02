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
import com.liftr.domain.model.DepthValidationResult
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PoseKeypoint
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

        /** Least visibility for a joint to be used in an angle. */
        private const val MIN_JOINT_VISIBILITY = 0.5f
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
        val lockout = if (sufficientHeight) checkLockout(poseResult) else null

        logger.d(TAG, "CONCENTRIC: liftHeight=%.2f m, sufficient=$sufficientHeight, lockout=${lockout?.achieved}".format(liftHeight))

        if (lockout != null && lockout.achieved) {
            logger.d(TAG, "Phase: CONCENTRIC -> TOP (rep completed)")
            return PhaseTransitionResult(
                newState = updatedState.withCurrentPhase(MovementPhase.TOP),
                repCompleted = true,
                debugInfo = "Rep completed (lockout): %.2f m pull".format(liftHeight),
                // A deadlift has no depth: its lockout takes that place in the rep score.
                depthValidation = lockout.toDepthValidation()
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
     * The lockout at the top of the pull, from the hip and knee angles.
     *
     * @property achieved Hips and knees extended (or, with neither angle measurable, the pull was high enough)
     * @property anglesMeasured How many of the two angles could be measured (0-2)
     */
    private data class Lockout(val achieved: Boolean, val anglesMeasured: Int) {
        /**
         * As a depth result: the fewer angles behind the lockout, the less sure
         * it is, which the scorer turns into fewer points (2 angles: 30, 1: 25, 0: 20).
         */
        fun toDepthValidation() = DepthValidationResult(
            isValid = achieved,
            confidence = when (anglesMeasured) {
                2 -> 1.0f
                1 -> 0.7f
                else -> 0.5f
            },
            methods = emptyList(),
            debugInfo = "Lockout from $anglesMeasured angle(s)"
        )
    }

    /**
     * Check if full lockout is achieved.
     * Requires both hip extension and knee extension above threshold angles.
     */
    private fun checkLockout(poseResult: PoseResult): Lockout {
        val hipAngle = calculateHipAngle(poseResult)
        val kneeAngle = calculateKneeAngle(poseResult)

        if (hipAngle == null && kneeAngle == null) {
            // If we can't compute either angle, allow lockout based on height alone
            return Lockout(achieved = true, anglesMeasured = 0)
        }

        val hipOk = hipAngle == null || hipAngle >= ExerciseThresholds.Deadlift.LOCKOUT_HIP_ANGLE
        val kneeOk = kneeAngle == null || kneeAngle >= ExerciseThresholds.Deadlift.LOCKOUT_KNEE_ANGLE

        logger.d(TAG, "Lockout check: hipAngle=${hipAngle?.let { "%.1f°".format(it) } ?: "N/A"}, " +
            "kneeAngle=${kneeAngle?.let { "%.1f°".format(it) } ?: "N/A"}, hipOk=$hipOk, kneeOk=$kneeOk")

        return Lockout(achieved = hipOk && kneeOk, anglesMeasured = listOfNotNull(hipAngle, kneeAngle).size)
    }

    /**
     * The given joints on the body side facing the camera, or null if neither
     * side has them all visible. Filmed side-on, the far side is hidden behind
     * the body, so requiring both sides would leave the angles unmeasured.
     * The side whose least visible joint is seen better wins.
     */
    private fun nearSide(poseResult: PoseResult, left: List<Int>, right: List<Int>): List<PoseKeypoint>? {
        fun side(indices: List<Int>): List<PoseKeypoint>? =
            indices.map { poseResult.landmarks.getOrNull(it) ?: return null }
        val candidates = listOfNotNull(side(left), side(right))
            .filter { joints -> joints.all { it.visibility >= MIN_JOINT_VISIBILITY } }
        return candidates.maxByOrNull { joints -> joints.minOf { it.visibility } }
    }

    /** Angle in degrees at the middle joint, on the side facing the camera. */
    private fun nearSideAngle(poseResult: PoseResult, left: List<Int>, right: List<Int>): Float? {
        val (a, b, c) = nearSide(poseResult, left, right) ?: return null
        val sx = poseResult.aspectScaleX
        return coordinateCalculator.angleBetweenPoints(
            ax = a.x * sx, ay = a.y,
            bx = b.x * sx, by = b.y,
            cx = c.x * sx, cy = c.y
        )
    }

    /**
     * Calculate hip angle: angle at hip formed by shoulder-hip-knee.
     * 180° = fully extended (standing tall), smaller = more bent.
     */
    private fun calculateHipAngle(poseResult: PoseResult): Float? = nearSideAngle(
        poseResult,
        left = listOf(PoseResult.LEFT_SHOULDER, PoseResult.LEFT_HIP, PoseResult.LEFT_KNEE),
        right = listOf(PoseResult.RIGHT_SHOULDER, PoseResult.RIGHT_HIP, PoseResult.RIGHT_KNEE)
    )

    /**
     * Calculate knee angle: angle at knee formed by hip-knee-ankle.
     * 180° = fully extended, smaller = more bent.
     */
    private fun calculateKneeAngle(poseResult: PoseResult): Float? = nearSideAngle(
        poseResult,
        left = listOf(PoseResult.LEFT_HIP, PoseResult.LEFT_KNEE, PoseResult.LEFT_ANKLE),
        right = listOf(PoseResult.RIGHT_HIP, PoseResult.RIGHT_KNEE, PoseResult.RIGHT_ANKLE)
    )

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

        val shoulderX = (leftShoulder.x + rightShoulder.x) / 2f * poseResult.aspectScaleX
        val shoulderY = (leftShoulder.y + rightShoulder.y) / 2f
        val hipX = (leftHip.x + rightHip.x) / 2f * poseResult.aspectScaleX
        val hipY = (leftHip.y + rightHip.y) / 2f

        val currentAngle = coordinateCalculator.torsoAngle(shoulderX, shoulderY, hipX, hipY)

        // Rounding shows up as the torso tipping further forward than it was at
        // setup while the bar is moving — the hips rising faster than the chest.
        // Both angles are computed with X converted to image-height units (the
        // setup angle from meters, this one via aspectScaleX), so they are
        // directly comparable.
        val angleIncrease = currentAngle - startingTorsoAngle
        val threshold = ExerciseThresholds.Deadlift.BACK_ROUNDING_THRESHOLD

        return when {
            angleIncrease > threshold -> FormIssue(
                kind = "back_rounding",
                severity = Severity.CRITICAL,
                message = "Back rounding detected (${angleIncrease.toInt()}° more than setup)",
                suggestion = "Keep chest up and back flat"
            )
            angleIncrease > threshold * 0.6f -> FormIssue(
                kind = "back_rounding",
                severity = Severity.WARNING,
                message = "Torso tipping forward under load (${angleIncrease.toInt()}°)",
                suggestion = "Engage lats and keep chest proud"
            )
            else -> null
        }
    }

    /**
     * Check if the bar drifts away from the body: seen from the side, the hands
     * (holding the bar) should stay over mid-foot through the pull. Drifting
     * forward moves the load away from the hips and increases lower back load.
     */
    private fun checkBarDrift(
        poseResult: PoseResult,
        calibrationData: CalibrationData
    ): FormIssue? {
        val (wrist, ankle, toes) = nearSide(
            poseResult,
            left = listOf(PoseResult.LEFT_WRIST, PoseResult.LEFT_ANKLE, PoseResult.LEFT_FOOT_INDEX),
            right = listOf(PoseResult.RIGHT_WRIST, PoseResult.RIGHT_ANKLE, PoseResult.RIGHT_FOOT_INDEX)
        ) ?: return null

        val midFootX = (ankle.x + toes.x) / 2f
        // Image-width units to image-height units, which pixelsToMeters takes.
        val driftUnits = abs(wrist.x - midFootX) * poseResult.aspectScaleX
        val drift = positionCalculator.pixelsToMeters(driftUnits, poseResult, calibrationData)

        return if (drift > ExerciseThresholds.Deadlift.BAR_DRIFT_MAX) {
            FormIssue(
                kind = "bar_drift",
                severity = Severity.WARNING,
                message = "Bar drifting away from mid-foot (%.0f cm)".format(drift * 100),
                suggestion = "Keep the bar close to your shins and thighs, over the middle of your foot"
            )
        } else null
    }

    override fun getRequiredLandmarks(): List<Int> = criteriaRef.get().targetBodyParts

    override fun getCriteria(): ExerciseCriteria = criteriaRef.get()
}
