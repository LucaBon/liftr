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
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.VelocityData
import com.liftr.domain.usecase.AnalyzeRepUseCase
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
        val (shoulder, elbow, wrist) = nearSide(
            poseResult,
            left = listOf(PoseResult.LEFT_SHOULDER, PoseResult.LEFT_ELBOW, PoseResult.LEFT_WRIST),
            right = listOf(PoseResult.RIGHT_SHOULDER, PoseResult.RIGHT_ELBOW, PoseResult.RIGHT_WRIST)
        ) ?: return null
        val sx = poseResult.aspectScaleX

        return coordinateCalculator.angleBetweenPoints(
            ax = shoulder.x * sx, ay = shoulder.y,
            bx = elbow.x * sx, by = elbow.y,
            cx = wrist.x * sx, cy = wrist.y
        )
    }

    private fun nearSide(poseResult: PoseResult, left: List<Int>, right: List<Int>): List<PoseKeypoint>? =
        nearSideLandmarks(poseResult, left, right, PoseKeypoint.VISIBILITY_THRESHOLD)

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
        currentState: ExercisePhaseState?,
        phase: MovementPhase?
    ): FormValidationResult {
        val issues = mutableListOf<FormIssue>()

        // Elbow flare and left/right press symmetry require a view along the bar
        // (phone past the lifter's head). The app films bench laterally, so those
        // checks are intentionally absent.
        // No incomplete-lockout check: a rep only completes once the near
        // elbow reaches LOCKOUT_MIN_ANGLE, so every counted rep is locked out.
        checkBarDrift(calibrationData, poseResult)?.let { issues.add(it) }

        return FormValidationResult(
            isValid = issues.none { it.severity == Severity.CRITICAL },
            issues = issues
        )
    }

    /**
     * Check horizontal bar drift from the shoulder, on the side facing the camera.
     */
    private fun checkBarDrift(calibrationData: CalibrationData, poseResult: PoseResult): FormIssue? {
        val (wrist, shoulder) = nearSide(
            poseResult,
            left = listOf(PoseResult.LEFT_WRIST, PoseResult.LEFT_SHOULDER),
            right = listOf(PoseResult.RIGHT_WRIST, PoseResult.RIGHT_SHOULDER)
        ) ?: return null

        // Image-width units to image-height units, which pixelsToMeters takes.
        val driftUnits = abs(wrist.x - shoulder.x) * poseResult.aspectScaleX
        val drift = positionCalculator.pixelsToMeters(driftUnits, poseResult, calibrationData)

        return if (drift > ExerciseThresholds.BenchPress.BAR_DRIFT_MAX) {
            FormIssue(
                kind = "bar_drift",
                severity = Severity.WARNING,
                message = "Bar drifting horizontally (%.0f cm)".format(drift * 100),
                suggestion = "Keep the bar path more vertical"
            )
        } else null
    }

    override fun validateRep(
        frames: List<AnalyzeRepUseCase.RepFrame>,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState?
    ): List<FormIssue> = listOfNotNull(checkChestPause(frames, calibrationData))

    /**
     * Chest pause: the bar rests at the wrist's lowest point. The pause is the
     * contiguous span of frames around that point whose wrist stays within
     * PAUSE_BAND of it, measured on frame timestamps (ms) so it holds at any
     * fps, on the median-filtered wrist height. Reported as an INFO issue carrying the duration in ms in
     * its message: touch_and_go when shorter than TOUCH_AND_GO_MAX_PAUSE_MS,
     * chest_pause otherwise. Reps without
     * a real descent (no chest touch in the frames) report nothing.
     */
    private fun checkChestPause(frames: List<AnalyzeRepUseCase.RepFrame>, calibrationData: CalibrationData): FormIssue? {
        // Median-filtered over time so a one-frame landmark jump is neither the
        // lowest point nor a break in the pause.
        val smoothed = rollingMedianOrNull(
            frames.map { it.pose.timestampUs },
            frames.map { (pose, _) ->
                nearSide(pose, left = listOf(PoseResult.LEFT_WRIST), right = listOf(PoseResult.RIGHT_WRIST))?.first()?.y
            },
            ExerciseThresholds.BenchPress.PAUSE_SMOOTH_MS
        )
        val samples = frames.indices.mapNotNull { i ->
            smoothed[i]?.let { Triple(frames[i].pose.timestamp, it, frames[i].pose) }
        }
        if (samples.size < 2) return null
        val low = samples.indices.maxByOrNull { samples[it].second } ?: return null
        val (_, lowY, lowPose) = samples[low]
        val highestBefore = samples.take(low + 1).minOf { it.second }
        val descent = positionCalculator.pixelsToMeters(lowY - highestBefore, lowPose, calibrationData)
        if (descent < getCriteria().minDescentDepth.value) return null

        fun atBottom(i: Int): Boolean =
            positionCalculator.pixelsToMeters(lowY - samples[i].second, samples[i].third, calibrationData) <=
                ExerciseThresholds.BenchPress.PAUSE_BAND
        var first = low
        while (first > 0 && atBottom(first - 1)) first--
        var last = low
        while (last < samples.lastIndex && atBottom(last + 1)) last++
        val pauseMs = samples[last].first - samples[first].first

        return if (pauseMs < ExerciseThresholds.BenchPress.TOUCH_AND_GO_MAX_PAUSE_MS) {
            FormIssue(
                kind = "touch_and_go",
                severity = Severity.INFO,
                message = "Touch-and-go: no pause on the chest ($pauseMs ms)",
                suggestion = "Pause the bar briefly on your chest before pressing",
                scored = false
            )
        } else {
            FormIssue(
                kind = "chest_pause",
                severity = Severity.INFO,
                message = "Chest pause: $pauseMs ms",
                suggestion = "",
                scored = false
            )
        }
    }

    override fun getRequiredLandmarks(): List<Int> = criteriaRef.get().targetBodyParts

    override fun getCriteria(): ExerciseCriteria = criteriaRef.get()
}
