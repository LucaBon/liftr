package com.lifthelper.domain.usecase

import androidx.camera.core.ImageProxy
import com.lifthelper.domain.error.LiftHelperError
import com.lifthelper.domain.error.PoseErrorCode
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.CalibrationQuality
import com.lifthelper.domain.model.DepthValidationResult
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.SquatDepthType
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.domain.exercise.ExercisePhaseState
import com.lifthelper.domain.exercise.ExerciseStrategyFactory
import com.lifthelper.domain.exercise.strategy.BackSquatStrategy
import com.lifthelper.domain.exercise.strategy.FrontSquatStrategy
import com.lifthelper.domain.repository.SettingsKeys
import com.lifthelper.domain.repository.SettingsRepository
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.vision.IPoseDetector
import com.lifthelper.domain.vision.IPositionCalculator
import com.lifthelper.domain.vision.IVelocityCalculator
import com.lifthelper.vision.tracking.MovementPhase
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Use case for processing workout camera frames on the hot path.
 *
 * Runs only the work that must happen every frame to count reps and drive the
 * real-time HUD: pose detection, velocity tracking, phase detection, and the
 * cheap depth validation that comes from the phase transition. Heavier form
 * validation and per-rep metric calculation are deferred to
 * [AnalyzeRepUseCase], which runs once per rep over a buffer of captured
 * frames so the per-frame budget is spent on motion tracking instead.
 */
class ProcessWorkoutFrameUseCase @Inject constructor(
    private val poseDetector: IPoseDetector,
    private val velocityCalculator: IVelocityCalculator,
    private val positionCalculator: IPositionCalculator,
    private val strategyFactory: ExerciseStrategyFactory,
    private val settingsRepository: SettingsRepository,
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
            // 1. Convert ImageProxy to CameraFrame and detect pose
            val cameraFrame = com.lifthelper.vision.adapter.ImageProxyAdapter.toCameraFrame(params.imageProxy)
            val poseResult = poseDetector.detectPose(cameraFrame)
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

            // 3. Get exercise strategy and configure squat depth if applicable
            val strategy = strategyFactory.getStrategy(params.exerciseType)
            configureSquatDepth(strategy, params.exerciseType)

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

            // 5. Check calibration quality
            //    Note: strategy.validateForm and strategy.calculateMetrics are
            //    intentionally NOT called here — they are the heavy per-frame
            //    work that's now deferred to [AnalyzeRepUseCase], which runs
            //    once per rep over a buffered sequence of poses.
            val calibrationQuality = positionCalculator.getCalibrationQuality(
                poseResult,
                params.calibrationData
            )

            return FrameProcessingResult.Success(
                poseResult = poseResult,
                velocityData = velocityData,
                phaseResult = phaseResult,
                calibrationQuality = calibrationQuality
            )

        } catch (e: LiftHelperError.PoseDetectionError) {
            val context = "exercise=${params.exerciseType.name}, timestamp=${params.timestamp}"
            logger.e(TAG, "Pose detection failed [$context]: ${e.message}", e)
            return FrameProcessingResult.Error(
                message = "Pose detection failed for ${params.exerciseType.name}: ${e.message ?: "Unknown error"}",
                cause = e
            )
        } catch (e: LiftHelperError) {
            val context = "exercise=${params.exerciseType.name}, timestamp=${params.timestamp}, recording=${params.isRecording}"
            logger.e(TAG, "Frame processing failed [$context]: ${e.message}", e)
            return FrameProcessingResult.Error(
                message = "Frame processing failed for ${params.exerciseType.name}: ${e.message ?: "Unknown error"}",
                cause = e
            )
        } catch (e: Exception) {
            val context = "exercise=${params.exerciseType.name}, timestamp=${params.timestamp}"
            logger.e(TAG, "Unexpected error [$context]", e)
            return FrameProcessingResult.Error(
                message = "Unexpected error processing ${params.exerciseType.name} frame: ${e.javaClass.simpleName}",
                cause = e
            )
        } finally {
            params.imageProxy.close()
        }
    }

    /**
     * Configures squat depth setting for squat exercise strategies.
     * Loads the user's preferred squat depth from settings and updates the strategy.
     */
    private suspend fun configureSquatDepth(
        strategy: com.lifthelper.domain.exercise.ExerciseStrategy,
        exerciseType: ExerciseType
    ) {
        // Only configure depth for squat exercises
        when (strategy) {
            is BackSquatStrategy -> {
                val depthTypeName = settingsRepository.getStringSetting(
                    SettingsKeys.SQUAT_DEPTH_BACK_SQUAT,
                    SquatDepthType.DEFAULT.name
                ).first()
                val depthType = SquatDepthType.fromString(depthTypeName)
                strategy.updateSquatDepthType(depthType)
            }
            is FrontSquatStrategy -> {
                val depthTypeName = settingsRepository.getStringSetting(
                    SettingsKeys.SQUAT_DEPTH_FRONT_SQUAT,
                    SquatDepthType.DEFAULT.name
                ).first()
                val depthType = SquatDepthType.fromString(depthTypeName)
                strategy.updateSquatDepthType(depthType)
            }
            else -> {
                // Not a squat exercise, no depth configuration needed
            }
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
            debugInfo = phaseTransitionResult.debugInfo,
            depthValidation = phaseTransitionResult.depthValidation
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
     * Frame processed successfully. Carries only hot-path data; heavier
     * per-rep form validation and metrics come from [AnalyzeRepUseCase].
     */
    data class Success(
        val poseResult: PoseResult,
        val velocityData: VelocityData?,
        val phaseResult: PhaseResult?,
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
    val debugInfo: String,
    val depthValidation: DepthValidationResult? = null
) {
    // Convenience property for backwards compatibility
    val phase: MovementPhase
        get() = newState.currentPhase
}
