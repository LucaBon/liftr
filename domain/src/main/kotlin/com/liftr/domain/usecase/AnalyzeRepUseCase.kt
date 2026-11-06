package com.liftr.domain.usecase

import com.liftr.domain.exercise.ExerciseMetrics
import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseStrategyFactory
import com.liftr.domain.exercise.FormIssue
import com.liftr.domain.exercise.FormValidationResult
import com.liftr.domain.exercise.worstPerKind
import com.liftr.domain.exercise.Severity
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.VelocityData
import com.liftr.domain.value.Kilograms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Post-hoc analysis for a just-completed rep. Runs the heavy
 * [ExerciseStrategy.validateForm] / [ExerciseStrategy.calculateMetrics] work
 * over a buffer of frames captured during the rep, off the camera hot path.
 *
 * The hot path ([ProcessWorkoutFrameUseCase]) only runs pose detection,
 * velocity, and phase/rep counting — the expensive form/metric passes run
 * here, typically during the rest between reps or sets.
 */
class AnalyzeRepUseCase @Inject constructor(
    private val strategyFactory: ExerciseStrategyFactory,
    private val logger: Logger
) {

    /** A buffered rep frame with the phase detected for it. */
    data class RepFrame(val pose: PoseResult, val phase: MovementPhase)

    data class Params(
        val exerciseType: ExerciseType,
        val calibrationData: CalibrationData,
        val weight: Kilograms,
        val bufferedFrames: List<RepFrame>,
        val finalVelocityData: VelocityData?,
        /**
         * Phase state of the rep being analysed. Form checks that are only
         * meaningful relative to the start of the rep (e.g. deadlift back
         * rounding, measured against the setup torso angle) need it.
         */
        val phaseState: ExercisePhaseState? = null
    )

    data class Result(
        val formValidation: FormValidationResult,
        val exerciseMetrics: ExerciseMetrics?
    )

    /**
     * Replays the whole buffered rep through the strategy's form/metric passes,
     * so it runs on [Dispatchers.Default] — callers invoke it from
     * `viewModelScope` (Main) at rep completion, mid-set.
     */
    suspend operator fun invoke(params: Params): Result = withContext(Dispatchers.Default) {
        if (params.bufferedFrames.isEmpty()) {
            return@withContext Result(
                formValidation = FormValidationResult(isValid = true, issues = emptyList()),
                exerciseMetrics = null
            )
        }

        val strategy = strategyFactory.getStrategy(params.exerciseType)

        val frameIssues = mutableListOf<FormIssue>()
        for ((pose, phase) in params.bufferedFrames) {
            try {
                frameIssues += strategy.validateForm(pose, params.calibrationData, params.phaseState, phase).issues
            } catch (e: Exception) {
                logger.w(TAG, "validateForm pass failed: ${e.message}")
            }
        }
        try {
            frameIssues += strategy.validateRep(params.bufferedFrames, params.calibrationData, params.phaseState)
        } catch (e: Exception) {
            logger.w(TAG, "validateRep pass failed: ${e.message}")
        }
        // A fault held over many frames is one fault for the rep, scored once at its worst.
        val aggregatedIssues = frameIssues.worstPerKind()

        val isValid = aggregatedIssues.none { it.severity == Severity.CRITICAL }
        val formValidation = FormValidationResult(isValid = isValid, issues = aggregatedIssues)

        val metrics = if (params.finalVelocityData != null) {
            runCatching {
                strategy.calculateMetrics(
                    poseResult = params.bufferedFrames.last().pose,
                    velocityData = params.finalVelocityData,
                    weight = params.weight
                )
            }.onFailure { logger.w(TAG, "calculateMetrics failed: ${it.message}") }
                .getOrNull()
        } else null

        Result(formValidation = formValidation, exerciseMetrics = metrics)
    }

    companion object {
        private const val TAG = "AnalyzeRepUseCase"
    }
}
