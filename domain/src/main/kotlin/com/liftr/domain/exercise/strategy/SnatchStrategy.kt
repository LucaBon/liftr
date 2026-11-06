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
import com.liftr.domain.model.CatchType
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.VelocityData
import com.liftr.domain.value.Joules
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Watts
import com.liftr.domain.vision.IPositionCalculator
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.usecase.AnalyzeRepUseCase
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.math.sign

/**
 * Strategy for the Olympic Snatch exercise.
 *
 * The snatch is a single-movement overhead lift (floor → overhead) — the bar travels
 * from the floor to a locked-out position overhead in one continuous pull, with the
 * lifter usually dropping into a squat to receive the bar overhead. A muscle snatch
 * (no re-bend under the bar) counts too; [classifyCatch] labels how each rep was caught.
 *
 * Phase mapping (repurposing MovementPhase enum):
 *   IDLE        → waiting for pull start
 *   ECCENTRIC   → FIRST_PULL  (wrists rising from floor to knees)
 *   BOTTOM      → SECOND_PULL (explosive hip extension + pull-under)
 *   CONCENTRIC  → CATCH + OVERHEAD_STABILIZE (receive bar overhead in squat, stabilize)
 *   TOP         → LOCKOUT (standing with bar overhead)
 *
 * Rep is counted at the CONCENTRIC → TOP transition: standing (hip and knee
 * angles at the deadlift lockout thresholds) after the catch, with the bar
 * not dropped from overhead.
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
                updatedState, currentWristsY, currentHipsY, poseResult.timestampUs
            )
            MovementPhase.ECCENTRIC -> detectFirstPullToSecondPull(
                updatedState, currentHipsY, currentWristsY, poseResult.timestampUs
            )
            MovementPhase.BOTTOM -> detectSecondPullToCatch(
                updatedState, currentWristsY, currentHeadY, currentHipsY, poseResult.timestampUs
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
        timestampUs: Long
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
                .withPreviousPositions(wristsY = wristsY, hipsY = hipsY, timestamp = timestampUs)

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
            timestamp = timestampUs
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
                    .withOlympicLiftData(firstPullStartTime = timestampUs / 1000),
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
        nowUs: Long
    ): PhaseTransitionResult {
        if (hipsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "No hip position"
            )
        }

        val hipVelocity = upwardVelocity(state.previousHipsY, hipsY, state.previousTimestamp, nowUs)

        val newState = state.withPreviousPositions(wristsY = wristsY, hipsY = hipsY, timestamp = nowUs)

        logger.d(TAG, "FIRST_PULL: hipVelocity=%.2f m/s".format(hipVelocity))

        if (hipVelocity > ExerciseThresholds.Snatch.FIRST_TO_SECOND_PULL_HIP_VELOCITY) {
            logger.d(TAG, "Phase: ECCENTRIC/FIRST_PULL -> BOTTOM/SECOND_PULL (hipVel: %.2f m/s)"
                .format(hipVelocity))
            return PhaseTransitionResult(
                newState = newState
                    .withCurrentPhase(MovementPhase.BOTTOM)
                    .withOlympicLiftData(secondPullStartTime = nowUs / 1000),
                repCompleted = false,
                debugInfo = "Second pull started: hipVel=%.2f m/s".format(hipVelocity)
            )
        }

        return PhaseTransitionResult(
            newState = newState,
            repCompleted = false,
            debugInfo = "First pull: hipVel=%.2f m/s".format(hipVelocity)
        )
    }

    /**
     * Upward speed (m/s) between the previous and this sample; 0 without a
     * previous one. Timestamps in microseconds so high-fps deltas stay exact.
     */
    private fun upwardVelocity(previousY: Float?, y: Float, previousTimestampUs: Long?, nowUs: Long): Float {
        if (previousY == null || previousTimestampUs == null) return 0f
        val deltaT = (nowUs - previousTimestampUs) / 1_000_000f
        // Smaller Y = higher, so rising is previousY - y.
        return if (deltaT > 0f) (previousY - y) / deltaT else 0f
    }

    /**
     * BOTTOM (SECOND_PULL) -> CONCENTRIC (CATCH)
     *
     * KEY DIFFERENCE from Clean: the snatch catch is received overhead: wrists at
     * least CATCH_WRIST_ABOVE_HEAD above the head. The hips need not drop (muscle
     * snatch); a bar flung up and falling back is filtered at the lockout,
     * which waits Catch.SETTLE_MS.
     */
    private fun detectSecondPullToCatch(
        state: ExercisePhaseState,
        wristsY: Float?,
        headY: Float?,
        hipsY: Float?,
        nowUs: Long
    ): PhaseTransitionResult {
        if (wristsY == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "No wrist position"
            )
        }

        val newState = state.withPreviousPositions(wristsY = wristsY, hipsY = hipsY, timestamp = nowUs)

        // Detect overhead catch: wrists are above (or at) head level
        // In our coordinate system: smaller Y = higher. Wrists above head means wristsY < headY.
        // We require: headY - wristsY >= CATCH_WRIST_ABOVE_HEAD
        val wristsOverhead = if (headY != null) {
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

        logger.d(TAG, "SECOND_PULL: wristsY=%.2f, headY=%.2f, overhead=$wristsOverhead"
            .format(wristsY, headY ?: Float.NaN))

        if (wristsOverhead) {
            logger.d(TAG, "Phase: BOTTOM/SECOND_PULL -> CONCENTRIC/CATCH (wrists overhead)")
            return PhaseTransitionResult(
                newState = newState
                    .withCurrentPhase(MovementPhase.CONCENTRIC)
                    .withOlympicLiftData(
                        catchPosition = wristsY,
                        catchTime = nowUs / 1000
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
     * (recovery). Lockout is detected when the hip and knee angles reach the
     * lockout thresholds (170°, a missing angle passes); a bar dropped from
     * overhead aborts to IDLE instead.
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

        // Still extended from the pull is not yet a lockout: wait for any re-bend to show.
        val settled = state.catchTime?.let { poseResult.timestampUs / 1000 - it >= ExerciseThresholds.Catch.SETTLE_MS } ?: true

        if (hipOk && kneeOk && settled) {
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

    /**
     * Per-frame checks while the bar is held overhead after the catch
     * (CONCENTRIC: catch and recovery). TOP frames are skipped: a rep's buffer
     * also holds the previous rep's bar coming down from lockout.
     */
    override fun validateForm(
        poseResult: PoseResult,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState?,
        phase: MovementPhase?
    ): FormValidationResult {
        val issues = mutableListOf<FormIssue>()

        if (phase == MovementPhase.CONCENTRIC && isOverhead(poseResult)) {
            checkPressOut(poseResult)?.let { issues.add(it) }
            checkBarBehindHead(poseResult, calibrationData)?.let { issues.add(it) }
        }

        return FormValidationResult(
            isValid = issues.none { it.severity == Severity.CRITICAL },
            issues = issues
        )
    }

    override fun validateRep(
        frames: List<AnalyzeRepUseCase.RepFrame>,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState?
    ): List<FormIssue> = listOfNotNull(
        // A muscle snatch has no catch depth; a power catch above the knees is a
        // chosen variant, so its depth is noted without costing points.
        when (classifyCatch(frames, calibrationData)) {
            CatchType.MUSCLE -> null
            CatchType.POWER -> checkCatchDepth(frames, calibrationData)?.copy(scored = false)
            else -> checkCatchDepth(frames, calibrationData)
        }
    )

    override fun classifyCatch(frames: List<AnalyzeRepUseCase.RepFrame>, calibrationData: CalibrationData): CatchType? =
        classifyCatch(frames, positionCalculator, calibrationData)

    /** Near-side wrist above the nose: the bar is overhead. */
    private fun isOverhead(poseResult: PoseResult): Boolean {
        val wrist = nearSide(poseResult, listOf(PoseResult.LEFT_WRIST), listOf(PoseResult.RIGHT_WRIST))?.first()
            ?: return false
        val nose = poseResult.landmarks.getOrNull(PoseResult.NOSE)
            ?.takeIf { it.visibility >= MIN_JOINT_VISIBILITY } ?: return false
        return wrist.y < nose.y
    }

    /**
     * Check catch depth: at the bottom of the catch (the lowest hips of the
     * rep's CONCENTRIC frames), the hips should be at or below the knees.
     * Larger Y = lower. Checked once per rep: during the recovery the hips
     * rise above the knees, which is no fault.
     */
    private fun checkCatchDepth(
        frames: List<AnalyzeRepUseCase.RepFrame>,
        calibrationData: CalibrationData
    ): FormIssue? {
        val bottom = frames
            .filter { it.phase == MovementPhase.CONCENTRIC }
            .mapNotNull { (pose, _) ->
                nearSide(
                    pose,
                    left = listOf(PoseResult.LEFT_HIP, PoseResult.LEFT_KNEE),
                    right = listOf(PoseResult.RIGHT_HIP, PoseResult.RIGHT_KNEE)
                )?.let { (hip, knee) -> Triple(pose, hip.y, knee.y) }
            }
            .maxByOrNull { (_, hipY, _) -> hipY } ?: return null

        val (pose, hipY, kneeY) = bottom
        val hipsBelowKnees = positionCalculator.pixelsToMeters(hipY - kneeY, pose, calibrationData)

        return if (hipsBelowKnees < ExerciseThresholds.Snatch.CATCH_DEPTH_THRESHOLD) {
            FormIssue(
                kind = "catch_depth",
                severity = Severity.INFO,
                message = "Insufficient catch depth",
                suggestion = "Drop under the bar deeper — catch with hips at or below knee level"
            )
        } else null
    }

    /**
     * Check bar position overhead, seen from the side: the wrists should not be
     * more than BAR_BEHIND_HEAD_MAX behind the nose. "Behind" is against the
     * way the lifter faces (nose ahead of the ear); a bar in front is not this fault.
     */
    private fun checkBarBehindHead(
        poseResult: PoseResult,
        calibrationData: CalibrationData
    ): FormIssue? {
        val (ear, wrist) = nearSide(
            poseResult,
            left = listOf(PoseResult.LEFT_EAR, PoseResult.LEFT_WRIST),
            right = listOf(PoseResult.RIGHT_EAR, PoseResult.RIGHT_WRIST)
        ) ?: return null
        val nose = poseResult.landmarks.getOrNull(PoseResult.NOSE)
            ?.takeIf { it.visibility >= MIN_JOINT_VISIBILITY } ?: return null

        val facing = sign(nose.x - ear.x)
        if (facing == 0f) return null
        // Image-width units to image-height units, which pixelsToMeters takes.
        val behindUnits = (nose.x - wrist.x) * facing * poseResult.aspectScaleX
        val behind = positionCalculator.pixelsToMeters(behindUnits, poseResult, calibrationData)

        return if (behind > ExerciseThresholds.Snatch.BAR_BEHIND_HEAD_MAX) {
            FormIssue(
                kind = "bar_behind_head",
                severity = Severity.WARNING,
                message = "Bar too far behind head (%.0f cm)".format(behind * 100),
                suggestion = "Keep the bar over your base of support — slightly behind the head is correct but not excessively"
            )
        } else null
    }

    /**
     * Check for press-out: overhead after the catch, the elbows should be locked
     * (arm angle ~180°). Bent elbows mean the lifter is pressing the bar out
     * (disqualified in competition).
     */
    private fun checkPressOut(poseResult: PoseResult): FormIssue? {
        val elbowAngle = calculateElbowAngle(poseResult) ?: return null

        return if (elbowAngle < ExerciseThresholds.Snatch.PRESS_OUT_ELBOW_ANGLE) {
            FormIssue(
                kind = "press_out",
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

    private fun nearSide(poseResult: PoseResult, left: List<Int>, right: List<Int>): List<PoseKeypoint>? =
        nearSideLandmarks(poseResult, left, right, MIN_JOINT_VISIBILITY)

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
     * 180° = fully extended (standing), smaller = more bent.
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

    /**
     * Calculate elbow angle: angle at elbow formed by shoulder-elbow-wrist.
     * 180° = fully extended (locked out), smaller = more bent.
     */
    private fun calculateElbowAngle(poseResult: PoseResult): Float? = nearSideAngle(
        poseResult,
        left = listOf(PoseResult.LEFT_SHOULDER, PoseResult.LEFT_ELBOW, PoseResult.LEFT_WRIST),
        right = listOf(PoseResult.RIGHT_SHOULDER, PoseResult.RIGHT_ELBOW, PoseResult.RIGHT_WRIST)
    )

    override fun getRequiredLandmarks(): List<Int> = criteriaRef.get().targetBodyParts

    override fun getCriteria(): ExerciseCriteria = criteriaRef.get()
}
