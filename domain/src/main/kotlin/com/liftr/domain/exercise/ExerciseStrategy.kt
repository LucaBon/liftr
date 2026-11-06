package com.liftr.domain.exercise

import com.liftr.domain.usecase.AnalyzeRepUseCase
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.DepthValidationResult
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.JumpData
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.SquatDepthType
import com.liftr.domain.model.VelocityData
import com.liftr.domain.value.Joules
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.Watts
import com.liftr.domain.model.MovementPhase

/**
 * Strategy interface for exercise-specific logic.
 *
 * Each exercise type (squat, bench press, etc.) implements this interface
 * to provide custom behavior for:
 * - Phase detection and transitions
 * - Exercise-specific metrics calculation
 * - Form validation
 * - Required landmarks
 */
interface ExerciseStrategy {
    /**
     * The type of exercise this strategy handles.
     */
    val exerciseType: ExerciseType

    /**
     * Detects the current movement phase and transitions.
     *
     * @param poseResult Current pose detection result
     * @param calibrationData User calibration data
     * @param currentState The current exercise phase state (immutable)
     * @return Result containing new state, whether a rep was completed, and debug info
     */
    fun detectPhase(
        poseResult: PoseResult,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState
    ): PhaseTransitionResult

    /**
     * Calculates exercise-specific metrics.
     *
     * @param poseResult Current pose detection result
     * @param velocityData Velocity and position data
     * @param weight The weight being lifted
     * @return Exercise-specific metrics including displacement, velocity, power, and work
     */
    fun calculateMetrics(
        poseResult: PoseResult,
        velocityData: VelocityData,
        weight: Kilograms
    ): ExerciseMetrics

    /**
     * Validates exercise form and provides feedback.
     *
     * @param poseResult Current pose detection result
     * @param calibrationData User calibration data
     * @param currentState Phase state for the rep being validated, when the
     *   caller has it. Checks that are only meaningful relative to the start of
     *   the rep (e.g. how far the torso angle has changed since the setup
     *   position) need this; checks that read a single frame ignore it. Null
     *   means those relative checks are skipped rather than guessed at.
     * @param phase Movement phase detected for this frame, when the caller has
     *   it (replayed rep frames carry it). Null when validating a lone frame.
     * @return Form validation result with issues and suggestions
     */
    fun validateForm(
        poseResult: PoseResult,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState? = null,
        phase: MovementPhase? = null
    ): FormValidationResult

    /**
     * Form checks over the whole rep, called once per rep with its frames in
     * order. Checks that need the rep's frames together (stalls, displacement
     * from the start, dwell times) go here, not in [validateForm]. Strategies
     * are shared singletons, so they must stay stateless: no per-rep fields.
     */
    fun validateRep(
        frames: List<AnalyzeRepUseCase.RepFrame>,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState? = null
    ): List<FormIssue> = emptyList()

    /**
     * Gets the list of required landmark indices for this exercise.
     *
     * @return List of MediaPipe landmark indices required for this exercise
     */
    fun getRequiredLandmarks(): List<Int>

    /**
     * Gets the exercise-specific criteria and thresholds.
     *
     * @return Exercise criteria including depth requirements, tolerances, etc.
     */
    fun getCriteria(): ExerciseCriteria
}

/**
 * Result of a phase transition detection.
 *
 * @property newState The new exercise phase state after transition (immutable)
 * @property repCompleted Whether a complete rep was just finished
 * @property debugInfo Debug information about the phase detection
 * @property depthValidation Depth validation result when transitioning through BOTTOM phase (squats only)
 * @property jump The measured jump when [repCompleted] (jumps only)
 */
data class PhaseTransitionResult(
    val newState: ExercisePhaseState,
    val repCompleted: Boolean,
    val debugInfo: String = "",
    val depthValidation: DepthValidationResult? = null,
    val jump: JumpData? = null
)

/**
 * Exercise-specific metrics calculated from pose and velocity data.
 *
 * @property displacement Total vertical displacement during the movement
 * @property peakVelocity Maximum velocity reached during the movement
 * @property avgPower Average power output during the movement
 * @property peakPower Maximum power output during the movement
 * @property work Total mechanical work performed (force × displacement)
 */
data class ExerciseMetrics(
    val displacement: Meters,
    val peakVelocity: MetersPerSecond,
    val avgPower: Watts,
    val peakPower: Watts,
    val work: Joules
)

/**
 * Result of form validation.
 *
 * @property isValid Whether the form is valid (no critical issues)
 * @property issues List of form issues detected
 */
data class FormValidationResult(
    val isValid: Boolean,
    val issues: List<FormIssue>
)

/**
 * A specific form issue detected during validation.
 *
 * @property severity The severity level of the issue
 * @property message Description of what's wrong
 * @property suggestion How to correct the issue
 * @property kind Which check raised the issue. Messages can carry the
 *   measured value ("forward lean (36°)"), so a fault seen across a rep's
 *   frames yields many messages; issues of one kind are one fault.
 */
data class FormIssue(
    val severity: Severity,
    val message: String,
    val suggestion: String,
    val kind: String = message
)

/**
 * One issue per [FormIssue.kind]: the most severe instance (the first one
 * seen on a tie), kept at the position where that kind first appeared.
 */
fun List<FormIssue>.worstPerKind(): List<FormIssue> {
    val worst = LinkedHashMap<String, FormIssue>()
    for (issue in this) {
        val current = worst[issue.kind]
        if (current == null || issue.severity > current.severity) worst[issue.kind] = issue
    }
    return worst.values.toList()
}

/**
 * Severity levels for form issues.
 */
enum class Severity {
    /** Informational feedback, not a problem */
    INFO,
    /** Form issue that should be corrected but not dangerous */
    WARNING,
    /** Critical form issue that could lead to injury */
    CRITICAL
}

/**
 * Exercise-specific criteria and thresholds.
 *
 * @property minDescentDepth Minimum depth the bar/body must descend
 * @property returnTolerance Tolerance for returning to starting position
 * @property minVelocity Minimum velocity threshold for movement detection
 * @property targetBodyParts List of landmark indices that are the focus of the exercise
 * @property squatDepthType The squat depth variation requirement (for squat exercises only)
 */
data class ExerciseCriteria(
    val minDescentDepth: Meters,
    val returnTolerance: Meters,
    val minVelocity: MetersPerSecond,
    val targetBodyParts: List<Int>,
    val squatDepthType: SquatDepthType = SquatDepthType.DEFAULT
)
