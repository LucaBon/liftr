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
 * Strategy for the Olympic Snatch exercise.
 *
 * The snatch is a single-movement overhead lift (floor → overhead) — the bar travels
 * from the floor to a locked-out position overhead in one continuous pull, with the
 * lifter dropping into a deep squat to receive the bar overhead.
 *
 * Phase mapping (repurposing MovementPhase enum):
 *   IDLE        → waiting for pull start
 *   ECCENTRIC   → FIRST_PULL  (wrists rising from floor to knees)
 *   BOTTOM      → SECOND_PULL (explosive hip extension + pull-under)
 *   CONCENTRIC  → CATCH + OVERHEAD_STABILIZE (receive bar overhead in squat, stabilize)
 *   TOP         → LOCKOUT (standing with bar overhead)
 *
 * Rep is counted at CONCENTRIC → TOP transition (full lockout overhead).
 */
class SnatchStrategy @Inject constructor(
    private val positionCalculator: IPositionCalculator,
    private val logger: Logger
) : ExerciseStrategy {

    override val exerciseType = ExerciseType.SNATCH

    private val coordinateCalculator = CoordinateCalculator()

    private val criteriaRef = AtomicReference(ExerciseCriteria(
        minDescentDepth = ExerciseThresholds.Snatch.MIN_PULL_HEIGHT,
        returnTolerance = ExerciseThresholds.Snatch.RETURN_TOLERANCE,
        minVelocity = ExerciseThresholds.Snatch.MIN_VELOCITY,
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
        private const val TAG = "SnatchStrategy"
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

        // Convert all needed positions to meters
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
        val currentWristsY = bodyPositions.wristsY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentWristsX = bodyPositions.wristsX?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentHeadY = bodyPositions.headY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }
        val currentElbowsY = bodyPositions.elbowsY?.let {
            positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
        }

        // Update state with current positions
        val updatedState = currentState
            .withCurrentPositions(
                headY = currentHeadY,
                shouldersY = currentShouldersY,
                hipsY = currentHipsY,
                kneesY = currentKneesY,
                hipsX = currentHipsX,
                kneesX = bodyPositions.kneesX?.let {
                    positionCalculator.pixelsToMeters(it, poseResult, calibrationData)
                }
            )
            .withAnklePositions(currentAnklesY, currentAnklesX)
            .withShouldersX(currentShouldersX)
            .withWristPositions(currentWristsY, currentWristsX)
            .withElbowData(currentElbowsY)

        // Snatch phase order: IDLE -> ECCENTRIC (FIRST_PULL) -> BOTTOM (SECOND_PULL)
        //                     -> CONCENTRIC (CATCH/OVERHEAD_STABILIZE) -> TOP (LOCKOUT) -> IDLE
        return when (updatedState.currentPhase) {
            MovementPhase.IDLE -> detectIdleToFirstPull(
                updatedState, currentWristsY, currentHipsY, poseResult.timestamp
            )
            MovementPhase.ECCENTRIC -> detectFirstPullToSecondPull(
                updatedState, currentHipsY, currentWristsY, poseResult.timestamp
            )
            MovementPhase.BOTTOM -> detectSecondPullToCatch(
                updatedState, currentWristsY, currentHeadY, currentHipsY, poseResult.timestamp
            )
            MovementPhase.CONCENTRIC -> detectCatchToLockout(
                updatedState, currentHipsY, currentWristsY, currentHeadY, poseResult
            )
            MovementPhase.TOP -> detectLockoutToIdle(updatedState, currentWristsY, currentHeadY)
        }
    }

    /**
     * IDLE -> ECCENTRIC (FIRST_PULL)
     *
     * Sets starting positions on the first frame, then waits for wrists to
     * rise above the IDLE_TO_FIRST_PULL_THRESHOLD.
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

        // Set starting positions on first detection
        if (state.startingHipsY == null) {
            val newState = state
                .withStartingPosition(state.currentHeadY, state.currentShouldersY, hipsY)
                .withStartingWristsY(wristsY)
                .withHighestHipsY(hipsY)
                .withPreviousPositions(wristsY = wristsY, hipsY = hipsY, timestamp = timestamp)

            logger.d(TAG, "Starting position set: wristsY=%.2f m, hipsY=%.2f m"
                .format(wristsY, hipsY ?: Float.NaN))
            return PhaseTransitionResult(
                newState = newState,
                repCompleted = false,
                debugInfo = "Starting position set"
            )
        }

        // Save previous frame data for velocity calculation
        val newState = state.withPreviousPositions(
            wristsY = wristsY,
            hipsY = hipsY,
            timestamp = timestamp
        )

        // Wrists rise = Y decreases (smaller Y = higher position).
        // Measured against the bar's resting position captured when the lift was
        // armed — NOT against the previous frame, which is refreshed every IDLE
        // frame and would require the whole threshold to be cleared within a
        // single frame interval (~0.75 m/s just to leave IDLE).
        val startingWristsY = state.startingWristsY ?: wristsY
        val wristRise = startingWristsY - wristsY

        if (wristRise > ExerciseThresholds.Snatch.IDLE_TO_FIRST_PULL_THRESHOLD) {
            logger.d(TAG, "Phase: IDLE -> ECCENTRIC/FIRST_PULL (wrist rise: %.2f m)".format(wristRise))
            return PhaseTransitionResult(
                newState = newState
                    .withCurrentPhase(MovementPhase.ECCENTRIC)
                    .withOlympicLiftData(firstPullStartTime = timestamp),
                repCompleted = false,
                debugInfo = "First pull started: %.2f m wrist rise".format(wristRise)
            )
        }

        return PhaseTransitionResult(
            newState = newState,
            repCompleted = false,
            debugInfo = "Waiting for pull"
        )
    }

    /**
     * ECCENTRIC (FIRST_PULL) -> BOTTOM (SECOND_PULL)
     *
     * Detects the explosive hip extension that signals the transition from the
     * first pull (bar to knees) to the second pull (knee to hip contact + shrug).
     * Hip velocity > FIRST_TO_SECOND_PULL_HIP_VELOCITY triggers the transition.
     */
    private fun detectFirstPullToSecondPull(
        state: ExercisePhaseState,
        hipsY: Float?,
        wristsY: Float?,
        now: Long
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

        // Calculate hip velocity (m/s); positive = upward (Y decreasing)
        val hipVelocity = if (previousHipsY != null && previousTimestamp != null) {
            val deltaT = (now - previousTimestamp) / 1000f
            if (deltaT > 0f) (previousHipsY - hipsY) / deltaT else 0f
        } else 0f

        val newState = state.withPreviousPositions(
            wristsY = wristsY,
            hipsY = hipsY,
            timestamp = now
        )

        logger.d(TAG, "FIRST_PULL: hipVelocity=%.2f m/s".format(hipVelocity))

        if (hipVelocity > ExerciseThresholds.Snatch.FIRST_TO_SECOND_PULL_HIP_VELOCITY) {
            logger.d(TAG, "Phase: ECCENTRIC/FIRST_PULL -> BOTTOM/SECOND_PULL (hipVel: %.2f m/s)"
                .format(hipVelocity))
            return PhaseTransitionResult(
                newState = newState
                    .withCurrentPhase(MovementPhase.BOTTOM)
                    .withOlympicLiftData(
                        peakPullVelocity = hipVelocity,
                        secondPullStartTime = now
                    ),
                repCompleted = false,
                debugInfo = "Second pull started: hipVel=%.2f m/s".format(hipVelocity)
            )
        }

        // Track peak hip velocity even before threshold is crossed
        val updatedPeak = if (hipVelocity > (state.peakPullVelocity ?: 0f)) hipVelocity
                          else state.peakPullVelocity
        return PhaseTransitionResult(
            newState = newState.withOlympicLiftData(peakPullVelocity = updatedPeak),
            repCompleted = false,
            debugInfo = "First pull: hipVel=%.2f m/s".format(hipVelocity)
        )
    }

    /**
     * BOTTOM (SECOND_PULL) -> CONCENTRIC (CATCH)
     *
     * KEY DIFFERENCE from Clean: the snatch catch is received overhead.
     * Detects catch when wrists are at or above head level (wristsY <= headY).
     * Condition: (headY - wristsY) >= CATCH_WRIST_ABOVE_HEAD, i.e. wrists are
     * at least CATCH_WRIST_ABOVE_HEAD above the head.
     */
    private fun detectSecondPullToCatch(
        state: ExercisePhaseState,
        wristsY: Float?,
        headY: Float?,
        hipsY: Float?,
        now: Long
    ): PhaseTransitionResult {
        if (wristsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "No wrist position"
            )
        }

        val previousHipsY = state.previousHipsY
        val previousTimestamp = state.previousTimestamp

        // Track peak pull velocity during second pull
        val hipVelocity = if (hipsY != null && previousHipsY != null && previousTimestamp != null) {
            val deltaT = (now - previousTimestamp) / 1000f
            if (deltaT > 0f) (previousHipsY - hipsY) / deltaT else 0f
        } else 0f

        val updatedPeak = if (hipVelocity > (state.peakPullVelocity ?: 0f)) hipVelocity
                          else state.peakPullVelocity

        val newState = state
            .withPreviousPositions(wristsY = wristsY, hipsY = hipsY, timestamp = now)
            .withOlympicLiftData(peakPullVelocity = updatedPeak)

        // Detect overhead catch: wrists are above (or at) head level
        // In our coordinate system: smaller Y = higher. Wrists above head means wristsY < headY.
        // We require: headY - wristsY >= CATCH_WRIST_ABOVE_HEAD
        val caughtOverhead = if (headY != null) {
            (headY - wristsY) >= ExerciseThresholds.Snatch.CATCH_WRIST_ABOVE_HEAD
        } else {
            // Fallback when the head landmark is occluded: assume the catch once
            // the wrists have risen well above the bar's resting position.
            // Must be measured from the armed starting position — comparing
            // against the previous frame would demand 0.3 m of travel inside a
            // single frame, so the lift would stay stuck in SECOND_PULL forever.
            val startingWristsY = state.startingWristsY
            startingWristsY != null && (startingWristsY - wristsY) > 0.3f
        }

        logger.d(TAG, "SECOND_PULL: wristsY=%.2f, headY=%.2f, overhead=$caughtOverhead"
            .format(wristsY, headY ?: Float.NaN))

        if (caughtOverhead) {
            logger.d(TAG, "Phase: BOTTOM/SECOND_PULL -> CONCENTRIC/CATCH (wrists overhead)")
            return PhaseTransitionResult(
                newState = newState
                    .withCurrentPhase(MovementPhase.CONCENTRIC)
                    .withOlympicLiftData(
                        catchPosition = wristsY,
                        catchTime = now
                    ),
                repCompleted = false,
                debugInfo = "Catch detected: wristsY=%.2f m".format(wristsY)
            )
        }

        return PhaseTransitionResult(
            newState = newState,
            repCompleted = false,
            debugInfo = "Second pull: wristsY=%.2f m".format(wristsY)
        )
    }

    /**
     * CONCENTRIC (CATCH/OVERHEAD_STABILIZE) -> TOP (LOCKOUT)
     *
     * The lifter has received the bar overhead in a squat and must now stand up
     * (recovery). Lockout is detected when the hip-knee-shoulder angle approaches
     * full extension (>= 170°) and wrists remain overhead.
     */
    private fun detectCatchToLockout(
        state: ExercisePhaseState,
        hipsY: Float?,
        wristsY: Float?,
        headY: Float?,
        poseResult: PoseResult
    ): PhaseTransitionResult {
        if (hipsY == null || wristsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        // Wrists must remain overhead during recovery
        val wristsStillOverhead = if (headY != null) {
            (headY - wristsY) >= -ExerciseThresholds.Snatch.OVERHEAD_STABILITY_DRIFT
        } else true

        if (!wristsStillOverhead) {
            // Bar dropped — abort and return to IDLE
            logger.d(TAG, "Phase: CONCENTRIC -> IDLE (bar dropped from overhead)")
            return PhaseTransitionResult(
                newState = state
                    .withCurrentPhase(MovementPhase.IDLE)
                    .withOlympicLiftData(
                        peakPullVelocity = null,
                        catchPosition = null,
                        firstPullStartTime = null,
                        secondPullStartTime = null,
                        catchTime = null
                    ),
                repCompleted = false,
                debugInfo = "Bar dropped overhead"
            )
        }

        // Check for standing lockout via hip angle (shoulder-hip-knee >= 170°)
        val hipAngle = calculateHipAngle(poseResult)
        val kneeAngle = calculateKneeAngle(poseResult)

        val hipOk = hipAngle == null || hipAngle >= ExerciseThresholds.Deadlift.LOCKOUT_HIP_ANGLE
        val kneeOk = kneeAngle == null || kneeAngle >= ExerciseThresholds.Deadlift.LOCKOUT_KNEE_ANGLE

        logger.d(TAG, "CONCENTRIC: hipAngle=${hipAngle?.let { "%.1f°".format(it) } ?: "N/A"}, " +
            "kneeAngle=${kneeAngle?.let { "%.1f°".format(it) } ?: "N/A"}, " +
            "hipOk=$hipOk, kneeOk=$kneeOk")

        if (hipOk && kneeOk) {
            logger.d(TAG, "Phase: CONCENTRIC/OVERHEAD_STABILIZE -> TOP/LOCKOUT (rep completed)")
            return PhaseTransitionResult(
                newState = state.withCurrentPhase(MovementPhase.TOP),
                repCompleted = true,
                debugInfo = "Rep completed: overhead lockout achieved"
            )
        }

        return PhaseTransitionResult(
            newState = state,
            repCompleted = false,
            debugInfo = "Recovery: hipAngle=${hipAngle?.let { "%.1f°".format(it) } ?: "N/A"}"
        )
    }

    /**
     * TOP (LOCKOUT) -> IDLE
     *
     * Detects when the bar is lowered from overhead — wrists drop significantly
     * below head level. Resets all Olympic lift fields for next rep.
     */
    private fun detectLockoutToIdle(
        state: ExercisePhaseState,
        wristsY: Float?,
        headY: Float?
    ): PhaseTransitionResult {
        if (wristsY == null || headY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "At overhead lockout"
            )
        }

        // Wrists dropped below head: wristsY > headY (larger Y = lower position)
        val wristsDropped = wristsY > headY

        if (wristsDropped) {
            logger.d(TAG, "Phase: TOP/LOCKOUT -> IDLE (bar lowered from overhead)")
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
                debugInfo = "Bar lowered from overhead — ready for next rep"
            )
        }

        return PhaseTransitionResult(
            newState = state,
            repCompleted = false,
            debugInfo = "Holding lockout overhead"
        )
    }

    // -------------------------------------------------------------------------
    // Form validation
    // -------------------------------------------------------------------------

    override fun validateForm(
        poseResult: PoseResult,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState?
    ): FormValidationResult {
        val issues = mutableListOf<FormIssue>()

        checkOverheadStability(poseResult, calibrationData)?.let { issues.add(it) }
        checkCatchDepth(poseResult)?.let { issues.add(it) }
        checkBarBehindHead(poseResult, calibrationData)?.let { issues.add(it) }
        checkPressOut(poseResult)?.let { issues.add(it) }

        return FormValidationResult(
            isValid = issues.none { it.severity == Severity.CRITICAL },
            issues = issues
        )
    }

    /**
     * Check overhead stability: wrist X position must not drift more than
     * OVERHEAD_STABILITY_DRIFT from the catch X position.
     */
    private fun checkOverheadStability(
        poseResult: PoseResult,
        calibrationData: CalibrationData
    ): FormIssue? {
        val bodyPositions = positionCalculator.getBodyPartPositions(poseResult) ?: return null
        if (bodyPositions.wristsX == null) return null

        val landmarks = poseResult.landmarks
        val leftWrist = landmarks.getOrNull(PoseResult.LEFT_WRIST)
        val rightWrist = landmarks.getOrNull(PoseResult.RIGHT_WRIST)
        val minVis = 0.5f
        if (leftWrist == null || rightWrist == null) return null
        if (leftWrist.visibility < minVis || rightWrist.visibility < minVis) return null

        // Check left-right wrist asymmetry as a proxy for instability overhead
        val leftWristX = positionCalculator.pixelsToMeters(leftWrist.x, poseResult, calibrationData)
        val rightWristX = positionCalculator.pixelsToMeters(rightWrist.x, poseResult, calibrationData)
        val asymmetry = abs(leftWristX - rightWristX)

        // Use OVERHEAD_STABILITY_DRIFT as a reasonable asymmetry threshold
        return if (asymmetry > ExerciseThresholds.Snatch.OVERHEAD_STABILITY_DRIFT) {
            FormIssue(
                severity = Severity.WARNING,
                message = "Overhead instability: wrist asymmetry (%.0f cm)".format(asymmetry * 100),
                suggestion = "Keep both wrists aligned overhead — engage your lats and keep the bar balanced"
            )
        } else null
    }

    /**
     * Check catch depth: at catch, hips should be at or below knee level.
     * In our coordinate system, hips below knees means hipsY > kneesY (larger Y = lower).
     * If hipsY < kneesY (hips above knees), depth is insufficient.
     */
    private fun checkCatchDepth(poseResult: PoseResult): FormIssue? {
        val landmarks = poseResult.landmarks
        val leftHip = landmarks.getOrNull(PoseResult.LEFT_HIP)
        val rightHip = landmarks.getOrNull(PoseResult.RIGHT_HIP)
        val leftKnee = landmarks.getOrNull(PoseResult.LEFT_KNEE)
        val rightKnee = landmarks.getOrNull(PoseResult.RIGHT_KNEE)

        val minVis = 0.5f
        if (leftHip == null || rightHip == null ||
            leftKnee == null || rightKnee == null) return null
        if (leftHip.visibility < minVis || rightHip.visibility < minVis ||
            leftKnee.visibility < minVis || rightKnee.visibility < minVis) return null

        val hipsY = (leftHip.y + rightHip.y) / 2f
        val kneesY = (leftKnee.y + rightKnee.y) / 2f

        // hipsY < kneesY means hips are higher than knees (insufficient depth)
        return if (hipsY < kneesY) {
            FormIssue(
                severity = Severity.INFO,
                message = "Insufficient catch depth",
                suggestion = "Drop under the bar deeper — catch with hips at or below knee level"
            )
        } else null
    }

    /**
     * Check bar position: wrists should not be more than BAR_BEHIND_HEAD_MAX
     * behind the head (in X). Excessive forward bar drift is also a concern.
     */
    private fun checkBarBehindHead(
        poseResult: PoseResult,
        calibrationData: CalibrationData
    ): FormIssue? {
        val bodyPositions = positionCalculator.getBodyPartPositions(poseResult) ?: return null
        val wristsX = bodyPositions.wristsX ?: return null

        val landmarks = poseResult.landmarks
        val nose = landmarks.getOrNull(PoseResult.NOSE)
        val minVis = 0.5f
        if (nose == null || nose.visibility < minVis) return null

        val wristsXMeters = positionCalculator.pixelsToMeters(wristsX, poseResult, calibrationData)
        val noseXMeters = positionCalculator.pixelsToMeters(nose.x, poseResult, calibrationData)

        val behindDistance = abs(wristsXMeters - noseXMeters)

        return if (behindDistance > ExerciseThresholds.Snatch.BAR_BEHIND_HEAD_MAX) {
            FormIssue(
                severity = Severity.WARNING,
                message = "Bar too far behind head (%.0f cm)".format(behindDistance * 100),
                suggestion = "Keep the bar over your base of support — slightly behind the head is correct but not excessively"
            )
        } else null
    }

    /**
     * Check for press-out: after the catch, elbows should be fully locked (arm angle ~180°).
     * If elbow angle < 160°, the lifter is pressing out the lift (disqualified in competition).
     */
    private fun checkPressOut(poseResult: PoseResult): FormIssue? {
        val elbowAngle = calculateElbowAngle(poseResult) ?: return null

        return if (elbowAngle < 160f) {
            FormIssue(
                severity = Severity.WARNING,
                message = "Press-out detected: elbow angle %.0f°".format(elbowAngle),
                suggestion = "Lock out the elbows at the catch — the bar must be caught with straight arms"
            )
        } else null
    }

    // -------------------------------------------------------------------------
    // Metrics
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Angle helpers
    // -------------------------------------------------------------------------

    /**
     * Calculate hip angle: angle at hip formed by shoulder-hip-knee.
     * 180° = fully extended (standing), smaller = more bent.
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

    /**
     * Calculate elbow angle: angle at elbow formed by shoulder-elbow-wrist.
     * 180° = fully extended (locked out), smaller = more bent.
     */
    private fun calculateElbowAngle(poseResult: PoseResult): Float? {
        val landmarks = poseResult.landmarks
        val leftShoulder = landmarks.getOrNull(PoseResult.LEFT_SHOULDER)
        val rightShoulder = landmarks.getOrNull(PoseResult.RIGHT_SHOULDER)
        val leftElbow = landmarks.getOrNull(PoseResult.LEFT_ELBOW)
        val rightElbow = landmarks.getOrNull(PoseResult.RIGHT_ELBOW)
        val leftWrist = landmarks.getOrNull(PoseResult.LEFT_WRIST)
        val rightWrist = landmarks.getOrNull(PoseResult.RIGHT_WRIST)

        val minVis = 0.5f
        if (leftShoulder == null || rightShoulder == null ||
            leftElbow == null || rightElbow == null ||
            leftWrist == null || rightWrist == null) return null
        if (leftShoulder.visibility < minVis || rightShoulder.visibility < minVis ||
            leftElbow.visibility < minVis || rightElbow.visibility < minVis ||
            leftWrist.visibility < minVis || rightWrist.visibility < minVis) return null

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

    override fun getRequiredLandmarks(): List<Int> = criteriaRef.get().targetBodyParts

    override fun getCriteria(): ExerciseCriteria = criteriaRef.get()
}
