package com.lifthelper.ui.screens.calibration

import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.data.local.preferences.SettingsDataStore
import com.lifthelper.domain.model.AnthropometricData
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.CalibrationQuality
import com.lifthelper.util.Constants.TAG_CALIBRATION
import com.lifthelper.vision.calculator.PositionCalculator
import com.lifthelper.vision.pose.PoseDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AnthropometricCalibrationState(
    val calibrationQuality: CalibrationQuality = CalibrationQuality.NONE,
    val isCapturing: Boolean = false,
    val captureCountdown: Int? = null,
    val captureComplete: Boolean = false,
    val anthropometricData: AnthropometricData? = null,
    val poseResult: com.lifthelper.domain.model.PoseResult? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class AnthropometricCalibrationViewModel @Inject constructor(
    private val poseDetector: PoseDetector,
    private val positionCalculator: PositionCalculator,
    private val settingsDataStore: SettingsDataStore,
    private val cameraManager: com.lifthelper.vision.camera.CameraManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnthropometricCalibrationState())
    val uiState: StateFlow<AnthropometricCalibrationState> = _uiState.asStateFlow()

    private var captureJob: Job? = null
    private var calibrationData: CalibrationData? = null

    init {
        Log.i(TAG_CALIBRATION, "AnthropometricCalibrationViewModel initialized")

        // Switch to high-accuracy model for calibration
        poseDetector.switchModel(com.lifthelper.util.Constants.POSE_MODEL_CALIBRATION)
        Log.i(TAG_CALIBRATION, "Using high-accuracy model: ${poseDetector.getCurrentModel()}")

        // Load existing calibration data
        viewModelScope.launch {
            settingsDataStore.calibrationData.collect { data ->
                calibrationData = data
            }
        }
    }

    /**
     * Process camera frame for pose detection
     */
    fun processFrame(imageProxy: ImageProxy) {
        val timestamp = System.currentTimeMillis()

        try {
            val poseResult = poseDetector.detectPose(imageProxy, timestamp)

            if (poseResult != null) {
                // Check calibration quality (full body visible?)
                val quality = positionCalculator.getCalibrationQuality(poseResult, calibrationData)

                _uiState.update { state ->
                    state.copy(
                        poseResult = poseResult,
                        calibrationQuality = quality
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG_CALIBRATION, "Frame processing error", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Start capture countdown
     */
    fun startCapture() {
        if (_uiState.value.isCapturing) return

        Log.i(TAG_CALIBRATION, "Starting anthropometric capture countdown")

        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            _uiState.update { it.copy(isCapturing = true, errorMessage = null) }

            // 10 second countdown for solo front camera mode (time to position yourself)
            for (i in 10 downTo 1) {
                _uiState.update { it.copy(captureCountdown = i) }
                delay(1000)
            }

            // Capture measurements
            captureMeasurements()
        }
    }

    /**
     * Capture anthropometric measurements from current pose
     */
    private suspend fun captureMeasurements() {
        _uiState.update { it.copy(captureCountdown = null) }

        val poseResult = _uiState.value.poseResult
        val quality = _uiState.value.calibrationQuality

        // Allow capture even if not optimal (for solo front camera mode)
        // The AnthropometricData validation will handle bad measurements
        if (poseResult == null) {
            Log.w(TAG_CALIBRATION, "Cannot capture: no pose detected")
            _uiState.update { it.copy(isCapturing = false) }
            return
        }

        // Warn if quality is not optimal but still proceed
        if (quality != CalibrationQuality.OPTIMAL) {
            Log.w(TAG_CALIBRATION, "Capturing with non-optimal quality: $quality")
        }

        // Get user height and shoulder width from calibration data
        val userHeight = calibrationData?.userHeight ?: 1.75f
        val userWeight = calibrationData?.anthropometricData?.userWeight
        val manualShoulderWidth = calibrationData?.shoulderWidth  // User's manually entered shoulder width

        // Calculate anthropometric data from pose
        val anthropometricData = AnthropometricData.fromPoseResult(
            poseResult = poseResult,
            userHeight = userHeight,
            userWeight = userWeight,
            manualShoulderWidth = manualShoulderWidth,  // Use manual shoulder width if available
            isFrontCamera = cameraManager.isFrontCamera()  // Mirror coordinates for front camera
        )

        if (anthropometricData != null && anthropometricData.isValid()) {
            Log.i(TAG_CALIBRATION, "✅ Anthropometric data captured successfully")
            Log.d(TAG_CALIBRATION, "Shoulder width: ${(anthropometricData.getShoulderWidth() * 100).toInt()}cm")
            Log.d(TAG_CALIBRATION, "Torso height: ${(anthropometricData.getTorsoHeight() * 100).toInt()}cm")
            Log.d(TAG_CALIBRATION, "Leg length: ${((anthropometricData.getUpperLegHeight() + anthropometricData.getLowerLegHeight()) * 100).toInt()}cm")

            _uiState.update { state ->
                state.copy(
                    anthropometricData = anthropometricData,
                    captureComplete = true,
                    isCapturing = false
                )
            }
        } else {
            // Log detailed failure reason
            val errorMsg = if (anthropometricData == null) {
                Log.e(TAG_CALIBRATION, "❌ Failed to capture: fromPoseResult returned null (missing critical landmarks)")
                "Could not detect body landmarks. Ensure head, shoulders, hips, and knees are visible."
            } else {
                Log.e(TAG_CALIBRATION, "❌ Failed to capture: data validation failed (unrealistic proportions)")
                Log.e(TAG_CALIBRATION, "   Shoulder width: ${(anthropometricData.getShoulderWidth() * 100).toInt()}cm")
                Log.e(TAG_CALIBRATION, "   Torso height: ${(anthropometricData.getTorsoHeight() * 100).toInt()}cm")
                "Measurements appear unrealistic. Try standing further back and more centered."
            }
            _uiState.update { it.copy(isCapturing = false, errorMessage = errorMsg) }
        }
    }

    /**
     * Save measurements to user profile
     */
    fun saveMeasurements() {
        val anthropometricData = _uiState.value.anthropometricData ?: return

        viewModelScope.launch {
            // Update calibration data with anthropometric measurements
            val updatedCalibration = calibrationData?.copy(
                anthropometricData = anthropometricData
            ) ?: CalibrationData.fromUserInput(
                heightCm = anthropometricData.userHeight * 100
            ).copy(anthropometricData = anthropometricData)

            // Save basic calibration data (anthropometric data will be stored separately)
            settingsDataStore.saveCalibration(updatedCalibration)

            Log.i(TAG_CALIBRATION, "✅ Anthropometric data saved to profile")
            Log.d(TAG_CALIBRATION, "Shoulder width: ${anthropometricData.getShoulderWidth()}m")
            Log.d(TAG_CALIBRATION, "Torso height: ${anthropometricData.getTorsoHeight()}m")
        }
    }

    /**
     * Reset capture state for retry
     */
    fun resetCapture() {
        captureJob?.cancel()
        _uiState.update { state ->
            state.copy(
                isCapturing = false,
                captureCountdown = null,
                captureComplete = false,
                anthropometricData = null
            )
        }
        Log.d(TAG_CALIBRATION, "Capture reset for retry")
    }

    override fun onCleared() {
        super.onCleared()
        captureJob?.cancel()

        // Switch back to fast model for workouts
        poseDetector.switchModel(com.lifthelper.util.Constants.POSE_MODEL_WORKOUT)
        Log.i(TAG_CALIBRATION, "Switched back to workout model: ${poseDetector.getCurrentModel()}")
    }
}
