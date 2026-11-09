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
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.SquatDepthType
import com.liftr.domain.model.VelocityData
import com.liftr.domain.usecase.AnalyzeRepUseCase
import com.liftr.domain.value.Joules
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.Watts
import com.liftr.domain.vision.IPositionCalculator
import com.liftr.domain.vision.PoseSanity
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Shared machinery of the barbell squat variants: bar-position phase detection
 * (IDLE -> ECCENTRIC -> BOTTOM -> CONCENTRIC -> TOP), depth validation, metrics
 * and the side-on form checks (torso angle, heel lift).
 *
 * Variants supply where the bar rides ([calculateBarPosition], [startingBarPosition]),
 * their thresholds and torso limits, and may add checks through [extraFormIssues].
 *
 * Phase detection:
 * 1. ECCENTRIC: bar descends past the idle-to-eccentric threshold from its start
 * 2. BOTTOM: hips reach the configured squat depth (Quarter/Half/Powerlifting/Full)
 * 3. CONCENTRIC: bar returns to within the return tolerance of its start
 */
abstract class SquatStrategyBase(
    protected val positionCalculator: IPositionCalculator,
    protected val logger: Logger,
    targetBodyParts: List<Int>,
    protected val tag: String,
    minDescentDepth: Meters,
    returnTolerance: Meters,
    minVelocity: MetersPerSecond
) : ExerciseStrategy {

    protected abstract val depthTolerance: Float
    protected abstract val idleToEccentricThreshold: Float
    protected abstract val bottomToConcentricThreshold: Float
    protected abstract val topStabilizationThreshold: Float

    /** Forward-lean limits and wording for the torso angle check. */
    protected abstract val torsoLimits: TorsoLimits

    /** Suggestion shown with the heel lift fault. */
    protected abstract val heelLiftSuggestion: String

    /**
     * Bar position in meters (larger = lower), from the head and shoulders Y
     * positions, or null if it cannot be determined.
     */
    protected abstract fun calculateBarPosition(headY: Float?, shouldersY: Float?): Float?

    /** Bar position at setup, or null while the starting position is not yet recorded. */
    protected abstract fun startingBarPosition(state: ExercisePhaseState): Float?

    /** Variant-specific form checks, run on every validated frame. */
    protected open fun extraFormIssues(poseResult: PoseResult): List<FormIssue> = emptyList()

    /** Limits for the forward-lean check; angles are degrees from vertical. */
    protected data class TorsoLimits(
        val kind: String,
        val criticalDegrees: Float,
        val warningDegrees: Float,
        val criticalSuggestion: String,
        val warningSuggestion: String
    )

    // Thread-safe: updated by settings changes, read during frame processing (BUG-B fix)
    private val criteriaRef = AtomicReference(ExerciseCriteria(
        minDescentDepth = minDescentDepth,
        returnTolerance = returnTolerance,
        minVelocity = minVelocity,
        targetBodyParts = targetBodyParts,
        squatDepthType = SquatDepthType.DEFAULT
    ))

    /**
     * Updates the squat depth type for this strategy.
     * This should be called when the user changes their depth preference in settings.
     *
     * @param depthType The new squat depth type to use
     */
    fun updateSquatDepthType(depthType: SquatDepthType) {
        criteriaRef.updateAndGet { it.copy(squatDepthType = depthType) }
        logger.d(tag, "Updated squat depth type to: ${depthType.displayName}")
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
        val positionedState = currentState.withCurrentPositions(
            headY = currentHeadY,
            shouldersY = currentShouldersY,
            hipsY = currentHipsY,
            kneesY = currentKneesY,
            hipsX = currentHipsX,
            kneesX = currentKneesX
        ).withAnklePositions(
            bodyPositions.anklesY?.let { positionCalculator.pixelsToMeters(it, poseResult, calibrationData) },
            bodyPositions.anklesX?.let { positionCalculator.pixelsToMeters(it, poseResult, calibrationData) }
        )
        val barPosition = calculateBarPosition(currentHeadY, currentShouldersY)

        // State machine for phase detection
        return when (positionedState.currentPhase) {
            MovementPhase.IDLE -> detectIdleTransition(positionedState, barPosition)
            MovementPhase.ECCENTRIC -> detectEccentricTransition(positionedState, barPosition)
            MovementPhase.BOTTOM -> detectBottomTransition(positionedState, barPosition)
            MovementPhase.CONCENTRIC -> detectConcentricTransition(positionedState, barPosition)
            MovementPhase.TOP -> detectTopTransition(positionedState, barPosition)
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
        val startingBar = startingBarPosition(state)
        if (startingBar == null) {
            val newState = state.withStartingPosition(
                state.currentHeadY,
                state.currentShouldersY,
                state.currentHipsY
            ).withLowestBarPosition(barPosition)

            logger.d(tag, "Starting position set: bar=%.2f m".format(barPosition))
            return PhaseTransitionResult(
                newState = newState,
                repCompleted = false,
                debugInfo = "Starting position set"
            )
        }

        val displacement = barPosition - startingBar

        // Start eccentric phase if moving down more than threshold
        if (displacement > idleToEccentricThreshold) {
            logger.d(tag, "Phase: IDLE -> ECCENTRIC (displacement: %.2f m)".format(displacement))
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
        val startingBar = startingBarPosition(state)

        if (barPosition == null || startingBar == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val descent = barPosition - startingBar

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

        logger.d(tag, "ECCENTRIC: descent=%.2f m, sufficientDepth=$sufficientDepth, hipsAtDepth=$hipsAtDepth".format(descent))

        if (sufficientDepth && hipsAtDepth) {
            logger.d(tag, "Phase: ECCENTRIC -> BOTTOM")
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

        // Check if starting to ascend (bar moving up from lowest point)
        val ascent = lowestBar - barPosition

        logger.d(tag, "BOTTOM: ascent=%.2f m".format(ascent))

        if (ascent > bottomToConcentricThreshold) {
            logger.d(tag, "Phase: BOTTOM -> CONCENTRIC")
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
        val startingBar = startingBarPosition(state)

        if (barPosition == null || startingBar == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val returnDistance = abs(barPosition - startingBar)

        logger.d(tag, "CONCENTRIC: returnDist=%.2f m".format(returnDistance))

        // Check if returned to starting position (within tolerance)
        if (returnDistance <= criteriaRef.get().returnTolerance.value) {
            logger.d(tag, "Phase: CONCENTRIC -> TOP (rep completed)")
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
        val startingBar = startingBarPosition(state)

        if (barPosition == null || startingBar == null) {
            return PhaseTransitionResult(
                newState = state,
                repCompleted = false,
                debugInfo = "Missing position data"
            )
        }

        val descent = barPosition - startingBar

        // Check if starting new rep (descending again)
        if (descent > idleToEccentricThreshold) {
            logger.d(tag, "Phase: TOP -> ECCENTRIC (new rep)")
            return PhaseTransitionResult(
                newState = state
                    .withCurrentPhase(MovementPhase.ECCENTRIC)
                    .withLowestBarPosition(barPosition)
                    .withDepthWindowSatisfied(false),
                repCompleted = false,
                debugInfo = "New rep started"
            )
        }

        // Check if stabilized at top (ready for next rep). The next rep starts
        // from here: re-anchor the start, so one taken while walking in or
        // unracking does not time every later rep against the wrong top.
        if (abs(descent) < topStabilizationThreshold) {
            logger.d(tag, "Phase: TOP -> IDLE")
            return PhaseTransitionResult(
                newState = state.withCurrentPhase(MovementPhase.IDLE)
                    .withStartingPosition(state.currentHeadY, state.currentShouldersY, state.currentHipsY),
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
     * Depth check of a single frame, with positions from [calculator]. The
     * video path finds the bottom from the bar track and judges its frames
     * here, with the analysis' own calculator (see [IPositionCalculator.newSession]).
     */
    fun validateDepthAt(
        poseResult: PoseResult,
        calibrationData: CalibrationData,
        calculator: IPositionCalculator
    ): DepthValidationResult? {
        val positions = calculator.getBodyPartPositions(poseResult) ?: return null
        fun meters(value: Float?) = value?.let { calculator.pixelsToMeters(it, poseResult, calibrationData) }
        val state = ExercisePhaseState.initial().withCurrentPositions(
            headY = meters(positions.headY),
            shouldersY = meters(positions.shouldersY),
            hipsY = meters(positions.hipsY),
            kneesY = meters(positions.kneesY),
            hipsX = meters(positions.hipsX),
            kneesX = meters(positions.kneesX)
        ).withAnklePositions(meters(positions.anklesY), meters(positions.anklesX))
        return validateSquatDepth(state)
    }

    /**
     * Validates squat depth using robust multi-method validation with confidence scoring.
     *
     * @return DepthValidationResult if sufficient landmark data is available, null otherwise
     */
    private fun validateSquatDepth(state: ExercisePhaseState): DepthValidationResult? {
        if (state.currentHipsY == null || state.currentKneesY == null) {
            logger.w(tag, "Cannot verify squat depth - missing hip or knee landmarks")
            return null
        }

        val depthType = criteriaRef.get().squatDepthType

        val validationResult = SquatDepthValidator.validateDepth(
            state = state,
            depthType = depthType,
            tolerance = Meters(depthTolerance)
        )

        if (validationResult.isValid) {
            logger.d(tag, "Squat depth PASS for ${depthType.displayName}")
        } else {
            logger.d(tag, "Squat depth FAIL for ${depthType.displayName}")
        }
        logger.d(tag, validationResult.debugInfo)

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
        currentState: ExercisePhaseState?,
        phase: MovementPhase?
    ): FormValidationResult {
        // A broken skeleton (plate hiding the upper body) would only report its own faults.
        if (!PoseSanity.isPlausible(poseResult)) return FormValidationResult(isValid = true, issues = emptyList())

        val issues = mutableListOf<FormIssue>()

        // Knee valgus/varus can only be measured from a frontal view; the app
        // is framed laterally for squats, so that check is intentionally absent.

        checkTorsoAngle(poseResult)?.let { issues.add(it) }
        issues.addAll(extraFormIssues(poseResult))

        // Consider form valid if no CRITICAL severity issues
        val isValid = issues.none { it.severity == Severity.CRITICAL }

        return FormValidationResult(
            isValid = isValid,
            issues = issues
        )
    }

    /** The given joints on the body side facing the camera (squats are filmed side-on). */
    private fun nearSide(poseResult: PoseResult, left: List<Int>, right: List<Int>): List<PoseKeypoint>? =
        nearSideLandmarks(poseResult, left, right, ExerciseThresholds.SquatForm.MIN_JOINT_VISIBILITY)

    /**
     * Check torso angle to detect excessive forward lean, measured on the
     * near-side shoulder-hip line with x scaled to the unit of y.
     */
    private fun checkTorsoAngle(poseResult: PoseResult): FormIssue? {
        val (shoulder, hip) = nearSide(
            poseResult,
            listOf(PoseResult.LEFT_SHOULDER, PoseResult.LEFT_HIP),
            listOf(PoseResult.RIGHT_SHOULDER, PoseResult.RIGHT_HIP)
        ) ?: return null

        val dx = abs(shoulder.x - hip.x) * poseResult.aspectScaleX
        val dy = abs(shoulder.y - hip.y)
        if (hypot(dx, dy) < ExerciseThresholds.SquatForm.MIN_TORSO_LENGTH) return null

        // Angle from vertical in degrees (0 = perfectly upright)
        val angleFromVertical = Math.toDegrees(atan2(dx, dy).toDouble()).toFloat()
        val limits = torsoLimits

        return when {
            angleFromVertical > limits.criticalDegrees -> FormIssue(
                kind = limits.kind,
                severity = Severity.CRITICAL,
                message = "Excessive forward lean (${angleFromVertical.toInt()}° from vertical)",
                suggestion = limits.criticalSuggestion
            )
            angleFromVertical > limits.warningDegrees -> FormIssue(
                kind = limits.kind,
                severity = Severity.WARNING,
                message = "Moderate forward lean (${angleFromVertical.toInt()}° from vertical)",
                suggestion = limits.warningSuggestion
            )
            else -> null
        }
    }

    /**
     * Foot pitch on the near side: (heel y - toe y) / foot length. A flat foot
     * keeps this constant; the heel rising relative to the toe makes it smaller.
     * Null when the foot is not visible or too foreshortened to read.
     */
    private fun footSlope(poseResult: PoseResult): Float? {
        val (heel, toe) = nearSide(
            poseResult,
            listOf(PoseResult.LEFT_HEEL, PoseResult.LEFT_FOOT_INDEX),
            listOf(PoseResult.RIGHT_HEEL, PoseResult.RIGHT_FOOT_INDEX)
        ) ?: return null
        val footLength = hypot((heel.x - toe.x) * poseResult.aspectScaleX, heel.y - toe.y)
        if (footLength < ExerciseThresholds.SquatForm.MIN_FOOT_LENGTH) return null
        return (heel.y - toe.y) / footLength
    }

    /**
     * Heel lift: over the rep, the heel rising relative to the toe. The foot
     * pitch of the first readable frame is the flat-foot reference; the rep
     * reports at most one issue. The pitch is median-filtered over
     * HEEL_LIFT_SMOOTH_MS, so a lift must be sustained, at any fps.
     */
    override fun validateRep(
        frames: List<AnalyzeRepUseCase.RepFrame>,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState?
    ): List<FormIssue> {
        // Median-filtered over time so a one-frame landmark jump is not a lift.
        // Broken skeletons are left out, not read as a foot.
        val plausible = frames.filter { PoseSanity.isPlausible(it.pose) }
        val slopes = rollingMedianOrNull(
            plausible.map { it.pose.timestampUs },
            plausible.map { footSlope(it.pose) },
            ExerciseThresholds.SquatForm.HEEL_LIFT_SMOOTH_MS
        ).filterNotNull()
        val reference = slopes.firstOrNull() ?: return emptyList()
        if (reference - slopes.min() <= ExerciseThresholds.SquatForm.HEEL_LIFT_WARNING) return emptyList()
        return listOf(
            FormIssue(
                kind = "foot_stability",
                severity = Severity.WARNING,
                message = "Heels lifting off ground",
                suggestion = heelLiftSuggestion
            )
        )
    }

    override fun getRequiredLandmarks(): List<Int> = criteriaRef.get().targetBodyParts

    override fun getCriteria(): ExerciseCriteria = criteriaRef.get()
}
