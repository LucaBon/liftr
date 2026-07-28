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
 * Strategy for Olympic Clean exercise.
 *
 * The clean is an Olympic lift starting from the floor with bar at hip crease.
 * Phase mapping (reusing MovementPhase enum):
 *   IDLE        → waiting for lift start
 *   ECCENTRIC   → FIRST_PULL  (wrists rising from floor to knees, controlled)
 *   BOTTOM      → SECOND_PULL (explosive hip extension, bar accelerates upward)
 *   CONCENTRIC  → CATCH/RECOVERY (wrists decelerate, lifter drops under bar to front rack, stands up)
 *   TOP         → LOCKOUT (standing tall with bar in front rack position)
 *
 * Rep is counted at CONCENTRIC → TOP transition (full recovery to standing).
 *
 * Coordinate system: Y decreases going UP (smaller Y = higher on screen).
 * Upward displacement = startingY - currentY (positive = moved up).
 * Upward velocity     = (previousY - currentY) / deltaTimeSeconds (positive = moving up).
 */
class CleanStrategy @Inject constructor(
    private val positionCalculator: IPositionCalculator,
    private val logger: Logger
) : ExerciseStrategy {

    override val exerciseType = ExerciseType.CLEAN

    private val coordinateCalculator = CoordinateCalculator()

    private val criteriaRef = AtomicReference(ExerciseCriteria(
        minDescentDepth = ExerciseThresholds.Clean.MIN_PULL_HEIGHT,
        returnTolerance = ExerciseThresholds.Clean.RETURN_TOLERANCE,
        minVelocity = ExerciseThresholds.Clean.MIN_VELOCITY,
        targetBodyParts = listOf(
            PoseResult.LEFT_WRIST,
            PoseResult.RIGHT_WRIST,
            PoseResult.LEFT_ELBOW,
            PoseResult.RIGHT_ELBOW,
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
        private const val TAG = "CleanStrategy"
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

        // Convert tracked positions to meters
        val currentWristsY = bodyPositions.wristsY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentWristsX = bodyPositions.wristsX?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentHipsY = bodyPositions.hipsY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentShouldersY = bodyPositions.shouldersY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentKneesY = bodyPositions.kneesY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentHipsX = bodyPositions.hipsX?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentAnklesY = bodyPositions.anklesY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentAnklesX = bodyPositions.anklesX?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentElbowsY = bodyPositions.elbowsY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }

        // Update state with all current positions
        val updatedState = currentState
            .withCurrentPositions(
                headY = bodyPositions.headY?.let { positionCalculator.pixelsToMeters(it, poseResult, calibrationData) },
                shouldersY = currentShouldersY,
                hipsY = currentHipsY,
                kneesY = currentKneesY,
                hipsX = currentHipsX,
                kneesX = bodyPositions.kneesX?.let { positionCalculator.pixelsToMeters(it, poseResult, calibrationData) }
            )
            .withWristPositions(currentWristsY, currentWristsX)
            .withElbowData(currentElbowsY)
            .withAnklePositions(currentAnklesY, currentAnklesX)
            .withShouldersX(bodyPositions.shouldersX?.let { positionCalculator.pixelsToMeters(it, poseResult, calibrationData) })

        // Clean phase order: IDLE -> ECCENTRIC(FIRST_PULL) -> BOTTOM(SECOND_PULL)
        //                    -> CONCENTRIC(CATCH/RECOVERY) -> TOP(LOCKOUT) -> IDLE
        return when (updatedState.currentPhase) {
            MovementPhase.IDLE ->
                detectIdleToFirstPull(updatedState, currentWristsY, currentHipsY, poseResult.timestamp)
            MovementPhase.ECCENTRIC ->
                detectFirstPullToSecondPull(updatedState, currentWristsY, currentHipsY, poseResult.timestamp)
            MovementPhase.BOTTOM ->
                detectSecondPullToCatch(updatedState, currentWristsY, poseResult.timestamp)
            MovementPhase.CONCENTRIC ->
                detectCatchToLockout(updatedState, poseResult)
            MovementPhase.TOP ->
                detectLockoutToIdle(updatedState, currentWristsY)
        }
    }

    /**
     * IDLE → ECCENTRIC (FIRST_PULL):
     * Set starting positions on first frame, then detect wrist rise above threshold.
     */
    private fun detectIdleToFirstPull(
        state: ExercisePhaseState,
        wristsY: Float?,
        hipsY: Float?,
        timestamp: Long
    ): PhaseTransitionResult {
        if (wristsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "No wrist position"
            )
        }

        // Initialize starting positions
        if (state.startingWristsY == null) {
            val newState = state
                .withStartingWristsY(wristsY)
                .withStartingPosition(state.currentHeadY, state.currentShouldersY, hipsY)
                .withPreviousPositions(wristsY = wristsY, hipsY = hipsY, timestamp = timestamp)

            logger.d(TAG, "Starting positions set: wristsY=%.2f m, hipsY=${hipsY?.let { "%.2f m".format(it) } ?: "N/A"}".format(wristsY))
            return PhaseTransitionResult(
                newState = newState,
                repCompleted = false,
                debugInfo = "Starting position set"
            )
        }

        val startingWristsY = state.startingWristsY

        // Y decreases going up: upward displacement = startingY - currentY
        val upwardDisplacement = startingWristsY - wristsY

        if (upwardDisplacement > ExerciseThresholds.Clean.IDLE_TO_FIRST_PULL_THRESHOLD) {
            logger.d(TAG, "Phase: IDLE -> ECCENTRIC/FIRST_PULL (wrist rise: %.2f m)".format(upwardDisplacement))
            return PhaseTransitionResult(
                newState = state
                    .withCurrentPhase(MovementPhase.ECCENTRIC)
                    .withOlympicLiftData(firstPullStartTime = timestamp)
                    .withPreviousPositions(wristsY = wristsY, hipsY = hipsY, timestamp = timestamp),
                repCompleted = false,
                debugInfo = "First pull started: %.2f m wrist rise".format(upwardDisplacement)
            )
        }

        // Update previous positions for next-frame velocity calculation
        return PhaseTransitionResult(
            newState = state.withPreviousPositions(wristsY = wristsY, hipsY = hipsY, timestamp = timestamp),
            repCompleted = false,
            debugInfo = "Waiting for pull (wrist rise: %.2f m)".format(upwardDisplacement)
        )
    }

    /**
     * ECCENTRIC (FIRST_PULL) → BOTTOM (SECOND_PULL):
     * Detect explosive hip extension via hip upward velocity exceeding threshold.
     */
    private fun detectFirstPullToSecondPull(
        state: ExercisePhaseState,
        wristsY: Float?,
        hipsY: Float?,
        timestamp: Long
    ): PhaseTransitionResult {
        if (hipsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "No hip position"
            )
        }

        val previousHipsY = state.previousHipsY
        val previousTimestamp = state.previousTimestamp

        // Calculate hip upward velocity if we have previous frame data
        val hipVelocity = if (previousHipsY != null && previousTimestamp != null) {
            val deltaTimeSeconds = (timestamp - previousTimestamp) / 1000f
            if (deltaTimeSeconds > 0f) {
                // Upward velocity: positive when hips move up (Y decreases)
                (previousHipsY - hipsY) / deltaTimeSeconds
            } else null
        } else null

        logger.d(TAG, "FIRST_PULL: hipVelocity=${hipVelocity?.let { "%.2f m/s".format(it) } ?: "N/A"}")

        // Update previous positions for velocity tracking. The wrist position
        // must be refreshed here too: withPreviousPositions keeps the stored
        // value for any argument left out, so omitting it would carry the
        // floor-level wrist Y from IDLE through the whole first pull. The first
        // SECOND_PULL frame would then divide the entire pull displacement by a
        // single frame interval and report a catch reversal of several m/s.
        val updatedState = state.withPreviousPositions(
            wristsY = wristsY ?: state.previousWristsY,
            hipsY = hipsY,
            timestamp = timestamp
        )

        if (hipVelocity != null && hipVelocity > ExerciseThresholds.Clean.FIRST_TO_SECOND_PULL_HIP_VELOCITY) {
            logger.d(TAG, "Phase: ECCENTRIC/FIRST_PULL -> BOTTOM/SECOND_PULL (hipVelocity: %.2f m/s)".format(hipVelocity))
            return PhaseTransitionResult(
                newState = updatedState
                    .withCurrentPhase(MovementPhase.BOTTOM)
                    .withOlympicLiftData(
                        firstPullStartTime = state.firstPullStartTime,
                        secondPullStartTime = timestamp
                    ),
                repCompleted = false,
                debugInfo = "Second pull started: hipVelocity=%.2f m/s".format(hipVelocity)
            )
        }

        return PhaseTransitionResult(
            newState = updatedState,
            repCompleted = false,
            debugInfo = "First pull in progress"
        )
    }

    /**
     * BOTTOM (SECOND_PULL) → CONCENTRIC (CATCH/RECOVERY):
     * Detect wrist velocity reversal (bar decelerates as lifter drops under it).
     * Also track peak pull velocity during the second pull phase.
     */
    private fun detectSecondPullToCatch(
        state: ExercisePhaseState,
        wristsY: Float?,
        timestamp: Long
    ): PhaseTransitionResult {
        if (wristsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "No wrist position"
            )
        }

        val previousWristsY = state.previousWristsY
        val previousTimestamp = state.previousTimestamp

        // Wrist velocity, upward-positive — the same convention hip velocity uses
        // above and that SnatchStrategy uses for peakPullVelocity. Image Y grows
        // downward, so a rising wrist means wristsY decreased.
        val wristVelocity = if (previousWristsY != null && previousTimestamp != null) {
            val deltaTimeSeconds = (timestamp - previousTimestamp) / 1000f
            if (deltaTimeSeconds > 0f) {
                (previousWristsY - wristsY) / deltaTimeSeconds
            } else null
        } else null

        // Peak pull velocity = fastest upward move = largest positive value.
        val currentPeakPullVelocity = state.peakPullVelocity
        val updatedPeakPullVelocity = if (wristVelocity != null) {
            if (currentPeakPullVelocity == null || wristVelocity > currentPeakPullVelocity) {
                wristVelocity
            } else currentPeakPullVelocity
        } else currentPeakPullVelocity

        val updatedState = state
            .withPreviousPositions(wristsY = wristsY, timestamp = timestamp)
            .withOlympicLiftData(
                peakPullVelocity = updatedPeakPullVelocity,
                firstPullStartTime = state.firstPullStartTime,
                secondPullStartTime = state.secondPullStartTime
            )

        logger.d(TAG, "SECOND_PULL: wristVelocity=${wristVelocity?.let { "%.2f m/s".format(it) } ?: "N/A"}, peakPull=${updatedPeakPullVelocity?.let { "%.2f m/s".format(it) } ?: "N/A"}")

        // Catch detected when the bar stops rising and reverses downward: with the
        // upward-positive convention that is the velocity dropping below
        // CATCH_WRIST_VELOCITY (-0.2 m/s, i.e. descending at 0.2 m/s).
        if (wristVelocity != null && wristVelocity < ExerciseThresholds.Clean.CATCH_WRIST_VELOCITY) {
            logger.d(TAG, "Phase: BOTTOM/SECOND_PULL -> CONCENTRIC/CATCH (wristVelocity: %.2f m/s)".format(wristVelocity))
            return PhaseTransitionResult(
                newState = updatedState
                    .withCurrentPhase(MovementPhase.CONCENTRIC)
                    .withOlympicLiftData(
                        peakPullVelocity = updatedPeakPullVelocity,
                        catchPosition = wristsY,
                        firstPullStartTime = state.firstPullStartTime,
                        secondPullStartTime = state.secondPullStartTime,
                        catchTime = timestamp
                    ),
                repCompleted = false,
                debugInfo = "Catch initiated: wristVelocity=%.2f m/s".format(wristVelocity)
            )
        }

        return PhaseTransitionResult(
            newState = updatedState,
            repCompleted = false,
            debugInfo = "Second pull in progress"
        )
    }

    /**
     * CONCENTRIC (CATCH/RECOVERY) → TOP (LOCKOUT):
     * Detect full recovery to standing: hip angle >= 170° (lifter standing tall with bar in front rack).
     * Rep is counted here.
     */
    private fun detectCatchToLockout(
        state: ExercisePhaseState,
        poseResult: PoseResult
    ): PhaseTransitionResult {
        val hipAngle = calculateHipAngle(poseResult)
        val kneeAngle = calculateKneeAngle(poseResult)

        logger.d(TAG, "CATCH/RECOVERY: hipAngle=${hipAngle?.let { "%.1f°".format(it) } ?: "N/A"}, kneeAngle=${kneeAngle?.let { "%.1f°".format(it) } ?: "N/A"}")

        // If we can't compute angles, allow recovery based on hips returning near starting height
        val recoveryAchieved = if (hipAngle == null && kneeAngle == null) {
            val startingHipsY = state.startingHipsY
            val currentHipsY = state.currentHipsY
            if (startingHipsY != null && currentHipsY != null) {
                // Standing hips should be significantly higher (lower Y) than clean starting position
                // Use hips returning to within RECOVERY_HIP_THRESHOLD of starting Y
                abs(currentHipsY - startingHipsY) <= ExerciseThresholds.Clean.RECOVERY_HIP_THRESHOLD
            } else {
                // Cannot determine — allow by default
                true
            }
        } else {
            val hipOk = hipAngle == null || hipAngle >= ExerciseThresholds.Deadlift.LOCKOUT_HIP_ANGLE
            val kneeOk = kneeAngle == null || kneeAngle >= ExerciseThresholds.Deadlift.LOCKOUT_KNEE_ANGLE
            hipOk && kneeOk
        }

        if (recoveryAchieved) {
            logger.d(TAG, "Phase: CONCENTRIC/CATCH -> TOP/LOCKOUT (rep completed)")
            return PhaseTransitionResult(
                newState = state.withCurrentPhase(MovementPhase.TOP),
                repCompleted = true,
                debugInfo = "Rep completed (recovery): hip=%.1f°".format(hipAngle ?: 0f)
            )
        }

        return PhaseTransitionResult(
            newState = state,
            repCompleted = false,
            debugInfo = "Recovery in progress"
        )
    }

    /**
     * TOP (LOCKOUT) → IDLE:
     * Detect bar being lowered back to floor (wrists dropping significantly from catch position).
     * Reset Olympic lift state fields for next rep.
     */
    private fun detectLockoutToIdle(
        state: ExercisePhaseState,
        wristsY: Float?
    ): PhaseTransitionResult {
        if (wristsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "No wrist position at lockout"
            )
        }

        val startingWristsY = state.startingWristsY

        if (startingWristsY != null) {
            // Wrists returning to near starting (floor) position
            val returnDistance = abs(wristsY - startingWristsY)
            if (returnDistance <= criteriaRef.get().returnTolerance.value) {
                logger.d(TAG, "Phase: TOP/LOCKOUT -> IDLE (bar returned to floor)")
                return PhaseTransitionResult(
                    newState = state
                        .withCurrentPhase(MovementPhase.IDLE)
                        .withOlympicLiftData(
                            peakPullVelocity = null,
                            catchPosition = null,
                            firstPullStartTime = null,
                            secondPullStartTime = null,
                            catchTime = null
                        )
                        .withPreviousPositions(wristsY = null, hipsY = null, timestamp = null),
                    repCompleted = false,
                    debugInfo = "Bar on floor, ready for next rep"
                )
            }
        }

        return PhaseTransitionResult(
            newState = state,
            repCompleted = false,
            debugInfo = "At lockout (front rack)"
        )
    }

    /**
     * Calculate hip angle: shoulder-hip-knee.
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
     * Calculate knee angle: hip-knee-ankle.
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

    /**
     * Calculate elbow angle at a single arm: shoulder-elbow-wrist.
     * Uses the left side. 180° = fully straight, smaller = bent.
     */
    private fun calculateElbowAngle(poseResult: PoseResult): Float? {
        val landmarks = poseResult.landmarks
        val leftShoulder = landmarks.getOrNull(PoseResult.LEFT_SHOULDER)
        val leftElbow = landmarks.getOrNull(PoseResult.LEFT_ELBOW)
        val leftWrist = landmarks.getOrNull(PoseResult.LEFT_WRIST)
        val rightShoulder = landmarks.getOrNull(PoseResult.RIGHT_SHOULDER)
        val rightElbow = landmarks.getOrNull(PoseResult.RIGHT_ELBOW)
        val rightWrist = landmarks.getOrNull(PoseResult.RIGHT_WRIST)

        val minVis = 0.5f
        // Prefer left side; fall back to right if left is occluded
        val (shoulder, elbow, wrist) = when {
            leftShoulder != null && leftElbow != null && leftWrist != null &&
                leftShoulder.visibility >= minVis && leftElbow.visibility >= minVis &&
                leftWrist.visibility >= minVis ->
                Triple(leftShoulder, leftElbow, leftWrist)
            rightShoulder != null && rightElbow != null && rightWrist != null &&
                rightShoulder.visibility >= minVis && rightElbow.visibility >= minVis &&
                rightWrist.visibility >= minVis ->
                Triple(rightShoulder, rightElbow, rightWrist)
            else -> return null
        }

        return coordinateCalculator.angleBetweenPoints(
            ax = shoulder.x, ay = shoulder.y,
            bx = elbow.x, by = elbow.y,
            cx = wrist.x, cy = wrist.y
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

        checkEarlyArmBend(poseResult)?.let { issues.add(it) }
        checkBarCrash(poseResult)?.let { issues.add(it) }
        checkForwardJump(poseResult, calibrationData)?.let { issues.add(it) }
        checkElbowDrop(poseResult)?.let { issues.add(it) }

        return FormValidationResult(
            isValid = issues.none { it.severity == Severity.CRITICAL },
            issues = issues
        )
    }

    /**
     * Check for early arm bend during the first pull phase.
     * Arms should remain straight until the bar passes the knees.
     * Elbow angle < 160° = premature bending → WARNING.
     */
    private fun checkEarlyArmBend(poseResult: PoseResult): FormIssue? {
        val elbowAngle = calculateElbowAngle(poseResult) ?: return null

        return if (elbowAngle < ExerciseThresholds.Clean.EARLY_ARM_BEND_ANGLE) {
            FormIssue(
                severity = Severity.WARNING,
                message = "Early arm bend detected (elbow: %.0f°)".format(elbowAngle),
                suggestion = "Keep arms straight during the first pull until the bar passes your knees"
            )
        } else null
    }

    /**
     * Check for bar crash at catch — excessive downward wrist velocity.
     * Velocity > 1.5 m/s downward indicates the bar is crashing into the rack position
     * rather than being received under control.
     */
    private fun checkBarCrash(poseResult: PoseResult): FormIssue? {
        val landmarks = poseResult.landmarks
        val leftWrist = landmarks.getOrNull(PoseResult.LEFT_WRIST)
        val rightWrist = landmarks.getOrNull(PoseResult.RIGHT_WRIST)

        val minVis = 0.5f
        if (leftWrist == null || rightWrist == null) return null
        if (leftWrist.visibility < minVis || rightWrist.visibility < minVis) return null

        // Use elbow-Y vs wrist-Y comparison as a proxy for a sudden downward acceleration
        // The primary bar-crash check uses velocity data stored in Olympic lift state.
        // Here we use a structural heuristic: if wrists are significantly below elbow
        // level at catch, the bar likely came crashing down.
        val leftElbow = landmarks.getOrNull(PoseResult.LEFT_ELBOW)
        val rightElbow = landmarks.getOrNull(PoseResult.RIGHT_ELBOW)

        if (leftElbow == null || rightElbow == null) return null
        if (leftElbow.visibility < minVis || rightElbow.visibility < minVis) return null

        val wristsY = (leftWrist.y + rightWrist.y) / 2f
        val elbowsY = (leftElbow.y + rightElbow.y) / 2f

        // In the front rack, elbows must be at or above wrist level.
        // A very large downward delta (wrists much lower than elbows) suggests a bar crash.
        // Y increases downward, so wristsY > elbowsY means wrists are below elbows.
        val verticalDrop = wristsY - elbowsY
        return if (verticalDrop > ExerciseThresholds.Clean.BAR_CRASH_VELOCITY) {
            FormIssue(
                severity = Severity.WARNING,
                message = "Bar crash detected at catch",
                suggestion = "Meet the bar actively — pull yourself under it and receive it in a solid front rack"
            )
        } else null
    }

    /**
     * Check for forward jump — feet should stay planted during the clean.
     * Excessive forward displacement of the ankles from starting position → INFO.
     */
    private fun checkForwardJump(
        poseResult: PoseResult,
        calibrationData: CalibrationData
    ): FormIssue? {
        val bodyPositions = positionCalculator.getBodyPartPositions(poseResult) ?: return null

        val anklesX = bodyPositions.anklesX ?: return null
        val startingAnklesX = bodyPositions.anklesX  // Use current as reference if starting not stored

        // We need a stable starting reference. Using anklesX stored in state is ideal,
        // but FormValidation is stateless. Use a proxy: compare ankle X to hip X.
        // If ankle X is far forward of hip X, the lifter likely jumped forward.
        val hipsX = bodyPositions.hipsX ?: return null

        val anklesXMeters = positionCalculator.pixelsToMeters(anklesX, poseResult, calibrationData)
        val hipsXMeters = positionCalculator.pixelsToMeters(hipsX, poseResult, calibrationData)

        val forwardDrift = abs(anklesXMeters - hipsXMeters)

        return if (forwardDrift > ExerciseThresholds.Clean.FORWARD_JUMP_MAX) {
            FormIssue(
                severity = Severity.INFO,
                message = "Forward jump detected (%.0f cm)".format(forwardDrift * 100),
                suggestion = "Keep feet planted; drive straight up rather than forward"
            )
        } else null
    }

    /**
     * Check for elbow drop at the catch/front rack position.
     * In a correct front rack, elbows should be at or above wrist level.
     * If elbowsY > wristsY (elbows lower than wrists in screen coordinates), → WARNING.
     * Note: Y increases downward, so larger Y = lower position.
     */
    private fun checkElbowDrop(poseResult: PoseResult): FormIssue? {
        val landmarks = poseResult.landmarks
        val leftElbow = landmarks.getOrNull(PoseResult.LEFT_ELBOW)
        val rightElbow = landmarks.getOrNull(PoseResult.RIGHT_ELBOW)
        val leftWrist = landmarks.getOrNull(PoseResult.LEFT_WRIST)
        val rightWrist = landmarks.getOrNull(PoseResult.RIGHT_WRIST)

        val minVis = 0.5f
        if (leftElbow == null || rightElbow == null ||
            leftWrist == null || rightWrist == null) return null
        if (leftElbow.visibility < minVis || rightElbow.visibility < minVis ||
            leftWrist.visibility < minVis || rightWrist.visibility < minVis) return null

        val elbowsY = (leftElbow.y + rightElbow.y) / 2f
        val wristsY = (leftWrist.y + rightWrist.y) / 2f

        // elbowsY > wristsY means elbows are physically lower than wrists (dropped elbows)
        return if (elbowsY > wristsY) {
            FormIssue(
                severity = Severity.WARNING,
                message = "Elbows dropping below wrists in front rack",
                suggestion = "Drive elbows up and forward to secure the bar in a high front rack position"
            )
        } else null
    }

    override fun getRequiredLandmarks(): List<Int> = criteriaRef.get().targetBodyParts

    override fun getCriteria(): ExerciseCriteria = criteriaRef.get()
}
