package com.lifthelper.domain.usecase

import com.lifthelper.domain.exercise.ExerciseMetrics
import com.lifthelper.domain.exercise.ExercisePhaseState
import com.lifthelper.domain.exercise.ExerciseStrategyFactory
import com.lifthelper.domain.exercise.FormIssue
import com.lifthelper.domain.exercise.FormValidationResult
import com.lifthelper.domain.exercise.Severity
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.domain.value.Kilograms
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

    data class Params(
        val exerciseType: ExerciseType,
        val calibrationData: CalibrationData,
        val weight: Kilograms,
        val bufferedPoses: List<PoseResult>,
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
        if (params.bufferedPoses.isEmpty()) {
            return@withContext Result(
                formValidation = FormValidationResult(isValid = true, issues = emptyList()),
                exerciseMetrics = null
            )
        }

        val strategy = strategyFactory.getStrategy(params.exerciseType)

        val aggregatedIssues = mutableListOf<FormIssue>()
        val seenMessages = mutableSetOf<String>()
        for (pose in params.bufferedPoses) {
            try {
                val result = strategy.validateForm(pose, params.calibrationData, params.phaseState)
                for (issue in result.issues) {
                    if (seenMessages.add(issue.message)) {
                        aggregatedIssues.add(issue)
                    }
                }
            } catch (e: Exception) {
                logger.w(TAG, "validateForm pass failed: ${e.message}")
            }
        }

        val isValid = aggregatedIssues.none { it.severity == Severity.CRITICAL }
        val formValidation = FormValidationResult(isValid = isValid, issues = aggregatedIssues)

        val metrics = if (params.finalVelocityData != null) {
            runCatching {
                strategy.calculateMetrics(
                    poseResult = params.bufferedPoses.last(),
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
