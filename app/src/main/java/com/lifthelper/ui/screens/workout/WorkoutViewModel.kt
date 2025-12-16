package com.lifthelper.ui.screens.workout

import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.domain.exercise.ExercisePhaseState
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.repository.CalibrationRepository
import com.lifthelper.domain.usecase.ProcessWorkoutFrameUseCase
import com.lifthelper.domain.usecase.FrameProcessingResult
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.RepCount
import com.lifthelper.ui.common.UiError
import com.lifthelper.ui.common.toUiError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkoutViewModel @Inject constructor(
    private val processFrameUseCase: ProcessWorkoutFrameUseCase,
    private val calibrationRepository: CalibrationRepository,
    private val logger: Logger
) : ViewModel() {

    companion object {
        private const val TAG = "WorkoutViewModel"
    }

    private val _uiState = MutableStateFlow(WorkoutState())
    val uiState: StateFlow<WorkoutState> = _uiState.asStateFlow()

    private var calibrationData: CalibrationData? = null
    private var exerciseState = ExercisePhaseState.initial()  // Immutable state for thread safety

    private var noPoseFrameCount = 0
    private var lastNoPoseLogTime = 0L

    init {
        logger.d(TAG, "WorkoutViewModel initialized")
        viewModelScope.launch {
            calibrationRepository.getCalibrationData().collect { calibration ->
                calibrationData = calibration
                logger.i(TAG, "Calibration data loaded: pixelsPerMeter=${calibration?.pixelsPerMeter}, isCalibrated=${calibration != null}")
            }
        }
    }

    /**
     * Process camera frame - delegated to use case
     */
    fun processFrame(imageProxy: ImageProxy) {
        viewModelScope.launch {
            val result = processFrameUseCase(
                ProcessWorkoutFrameUseCase.Params(
                    imageProxy = imageProxy,
                    timestamp = System.currentTimeMillis(),
                    calibrationData = calibrationData,
                    exerciseType = _uiState.value.selectedExercise,
                    isRecording = _uiState.value.isRecording,
                    exerciseState = exerciseState,  // Pass immutable state
                    currentRepCount = _uiState.value.repCount.value,
                    weight = _uiState.value.weight
                )
            )

            handleFrameProcessingResult(result)
        }
    }

    private fun handleFrameProcessingResult(result: FrameProcessingResult) {
        when (result) {
            is FrameProcessingResult.Success -> {
                // Update exercise state with new immutable state from phase detection
                result.phaseResult?.let { phaseResult ->
                    exerciseState = phaseResult.newState
                }

                _uiState.update { state ->
                    state.copy(
                        poseResult = result.poseResult,
                        velocityData = result.velocityData,
                        repCount = result.phaseResult?.repCount?.let { RepCount(it) } ?: state.repCount,
                        calibrationQuality = result.calibrationQuality,
                        error = null  // Clear any previous errors
                    )
                }
            }

            is FrameProcessingResult.NoPoseDetected -> {
                noPoseFrameCount++
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastNoPoseLogTime > 2000) {
                    logger.w(TAG, "No pose detected (missed $noPoseFrameCount frames in last 2s)")
                    noPoseFrameCount = 0
                    lastNoPoseLogTime = currentTime
                }
            }

            is FrameProcessingResult.NoCalibration -> {
                if (System.currentTimeMillis() % 5000 < 50) {
                    logger.w(TAG, "No calibration data available")
                }
            }

            is FrameProcessingResult.Error -> {
                logger.e(TAG, "Frame processing error: ${result.message}")
                result.cause?.let { error ->
                    _uiState.update { state ->
                        state.copy(
                            error = error.toUiError(
                                onRetry = { /* Retry logic if needed */ }
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Clears the current error state.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun startRecording() {
        logger.i(TAG, "START RECORDING - Exercise: ${_uiState.value.selectedExercise}, Weight: ${_uiState.value.weight.value}kg")
        noPoseFrameCount = 0
        lastNoPoseLogTime = 0L
        exerciseState = ExercisePhaseState.initial()  // Reset to fresh state
        _uiState.update { it.copy(isRecording = true, repCount = RepCount(0), error = null) }
        logger.d(TAG, "Recording state updated, exercise state reset")
    }

    fun stopRecording() {
        logger.i(TAG, "STOP RECORDING - Total reps: ${_uiState.value.repCount}")
        _uiState.update { it.copy(isRecording = false) }
    }

    fun setWeight(weight: Float) {
        _uiState.update { it.copy(weight = Kilograms(weight)) }
    }

    fun setTargetReps(reps: Int) {
        _uiState.update { it.copy(targetReps = RepCount(reps)) }
    }

    fun setExercise(exercise: com.lifthelper.domain.model.ExerciseType) {
        logger.i(TAG, "Exercise changed to: $exercise")
        _uiState.update { it.copy(selectedExercise = exercise) }
    }
}
