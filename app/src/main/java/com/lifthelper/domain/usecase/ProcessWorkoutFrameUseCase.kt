package com.lifthelper.domain.usecase

import androidx.camera.core.ImageProxy
import com.lifthelper.domain.error.LiftHelperError
import com.lifthelper.domain.error.PoseErrorCode
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.CalibrationQuality
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.domain.exercise.ExercisePhaseState
import com.lifthelper.domain.exercise.ExerciseStrategyFactory
import com.lifthelper.domain.exercise.FormValidationResult
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.vision.IPoseDetector
import com.lifthelper.domain.vision.IPositionCalculator
import com.lifthelper.domain.vision.IVelocityCalculator
import com.lifthelper.vision.tracking.MovementPhase
import javax.inject.Inject

/**
 * Use case for processing workout camera frames.
 *
 * Orchestrates pose detection, velocity calculation, phase detection,
 * form validation, and rep counting for workout sessions using exercise-specific strategies.
 */
class ProcessWorkoutFrameUseCase @Inject constructor(
    private val poseDetector: IPoseDetector,
    private val velocityCalculator: IVelocityCalculator,
    private val positionCalculator: IPositionCalculator,
    private val strategyFactory: ExerciseStrategyFactory,
    private val logger: Logger
) : UseCase<ProcessWorkoutFrameUseCase.Params, FrameProcessingResult>() {

    companion object {
        private const val TAG = "ProcessFrameUseCase"
    }

    /**
     * Processes a camera frame for workout tracking.
     *
     * @param params Parameters including image, calibration, and current state
     * @return Result of frame processing
     */
    override suspend fun invoke(params: Params): FrameProcessingResult {
        try {
            // 1. Detect pose
            val poseResult = poseDetector.detectPose(params.imageProxy, params.timestamp)
                ?: return FrameProcessingResult.NoPoseDetected

            // Check if we have calibration data
            if (params.calibrationData == null) {
                return FrameProcessingResult.NoCalibration
            }

            // 2. Calculate velocity
            val velocityData = velocityCalculator.calculate(
                poseResult = poseResult,
                exerciseType = params.exerciseType,
                calibrationData = params.calibrationData,
                timestamp = params.timestamp
            )

            // 3. Get exercise strategy
            val strategy = strategyFactory.getStrategy(params.exerciseType)

            // 4. Detect movement phase and count reps (only when recording)
            val phaseResult = if (params.isRecording) {
                detectPhaseAndCountReps(
                    poseResult = poseResult,
                    calibrationData = params.calibrationData,
                    exerciseType = params.exerciseType,
                    exerciseState = params.exerciseState,
                    currentRepCount = params.currentRepCount
                )
            } else {
                null
            }

            // 5. Validate form
            val formValidation = strategy.validateForm(poseResult, params.calibrationData)

            // 6. Calculate exercise metrics
            val exerciseMetrics = if (velocityData != null) {
                strategy.calculateMetrics(poseResult, velocityData, params.weight)
            } else {
                null
            }

            // 7. Check calibration quality
            val calibrationQuality = positionCalculator.getCalibrationQuality(
                poseResult,
                params.calibrationData
            )

            return FrameProcessingResult.Success(
                poseResult = poseResult,
                velocityData = velocityData,
                phaseResult = phaseResult,
                formValidation = formValidation,
                exerciseMetrics = exerciseMetrics,
                calibrationQuality = calibrationQuality
            )

        } catch (e: LiftHelperError.PoseDetectionError) {
            logger.e(TAG, "Pose detection failed: ${e.message}", e)
            return FrameProcessingResult.Error(
                message = e.message ?: "Pose detection failed",
                cause = e
            )
        } catch (e: LiftHelperError) {
            logger.e(TAG, "Frame processing failed: ${e.message}", e)
            return FrameProcessingResult.Error(
                message = e.message ?: "Unknown error",
                cause = e
            )
        } catch (e: Exception) {
            logger.e(TAG, "Unexpected frame processing error", e)
            return FrameProcessingResult.Error(
                message = "Unexpected error processing frame",
                cause = e
            )
        } finally {
            params.imageProxy.close()
        }
    }

    /**
     * Detects movement phase and counts reps using exercise-specific strategy.
     */
    private fun detectPhaseAndCountReps(
        poseResult: PoseResult,
        calibrationData: CalibrationData,
        exerciseType: ExerciseType,
        exerciseState: ExercisePhaseState,
        currentRepCount: Int
    ): PhaseResult {
        // Get exercise strategy
        val strategy = strategyFactory.getStrategy(exerciseType)

        // Detect phase using strategy (pass immutable state, get new state back)
        val phaseTransitionResult = strategy.detectPhase(
            poseResult = poseResult,
            calibrationData = calibrationData,
            currentState = exerciseState
        )

        // Calculate new rep count
        val newRepCount = if (phaseTransitionResult.repCompleted) {
            currentRepCount + 1
        } else {
            currentRepCount
        }

        if (phaseTransitionResult.repCompleted) {
            logger.i(TAG, "Rep completed: $newRepCount | ${phaseTransitionResult.debugInfo}")
        }

        // Log phase transitions
        if (exerciseState.currentPhase != phaseTransitionResult.newState.currentPhase) {
            logger.d(TAG, "Phase transition: ${exerciseState.currentPhase} -> ${phaseTransitionResult.newState.currentPhase}")
        }

        return PhaseResult(
            newState = phaseTransitionResult.newState,
            repCounted = phaseTransitionResult.repCompleted,
            repCount = newRepCount,
            debugInfo = phaseTransitionResult.debugInfo
        )
    }

    /**
     * Parameters for frame processing.
     */
    data class Params(
        val imageProxy: ImageProxy,
        val timestamp: Long,
        val calibrationData: CalibrationData?,
        val exerciseType: ExerciseType,
        val isRecording: Boolean,
        val exerciseState: ExercisePhaseState,  // Immutable state for thread safety
        val currentRepCount: Int,
        val weight: Kilograms = Kilograms(0f) // Weight being lifted
    )
}

/**
 * Result of frame processing.
 */
sealed class FrameProcessingResult {
    /**
     * Frame processed successfully.
     */
    data class Success(
        val poseResult: PoseResult,
        val velocityData: VelocityData?,
        val phaseResult: PhaseResult?,
        val formValidation: FormValidationResult,
        val exerciseMetrics: com.lifthelper.domain.exercise.ExerciseMetrics?,
        val calibrationQuality: CalibrationQuality
    ) : FrameProcessingResult()

    /**
     * No pose detected in the frame.
     */
    object NoPoseDetected : FrameProcessingResult()

    /**
     * No calibration data available.
     */
    object NoCalibration : FrameProcessingResult()

    /**
     * Error occurred during processing.
     */
    data class Error(
        val message: String,
        val cause: Throwable?
    ) : FrameProcessingResult()
}

/**
 * Result of phase detection and rep counting.
 */
data class PhaseResult(
    val newState: ExercisePhaseState,  // Updated immutable state
    val repCounted: Boolean,
    val repCount: Int,
    val debugInfo: String
) {
    // Convenience property for backwards compatibility
    val phase: MovementPhase
        get() = newState.currentPhase
}
