package com.lifthelper.ui.screens.calibration

import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.domain.model.AnthropometricData
import com.lifthelper.domain.repository.CalibrationRepository
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
    val errorMessage: String? = null,
    val isBuffering: Boolean = false,  // True during final 2 seconds when collecting frames
    val bodyCenterX: Float? = null,  // Horizontal center position of body (0-1 range)
    val alignmentMessage: String? = null  // Current alignment guidance message
)

@HiltViewModel
class AnthropometricCalibrationViewModel @Inject constructor(
    private val poseDetector: PoseDetector,
    private val positionCalculator: PositionCalculator,
    private val calibrationRepository: CalibrationRepository,
    private val cameraManager: com.lifthelper.vision.camera.CameraManager,
    private val anthropometricDataBuilder: com.lifthelper.domain.builder.AnthropometricDataBuilder,
    private val coordinateCalculator: com.lifthelper.domain.calculator.CoordinateCalculator,
    private val cameraAlignmentChecker: com.lifthelper.vision.calculator.CameraAlignmentChecker
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnthropometricCalibrationState())
    val uiState: StateFlow<AnthropometricCalibrationState> = _uiState.asStateFlow()

    private var captureJob: Job? = null
    private var calibrationData: CalibrationData? = null

    // Frame buffer for robust capture (collect multiple frames during last 2 seconds)
    private val frameBuffer = mutableListOf<com.lifthelper.domain.model.PoseResult>()
    private val maxBufferSize = 10  // Collect ~10 frames over 2 seconds

    init {
        Log.i(TAG_CALIBRATION, "AnthropometricCalibrationViewModel initialized")

        // Switch to high-accuracy model for calibration
        poseDetector.switchModel(com.lifthelper.util.Constants.POSE_MODEL_CALIBRATION)
        Log.i(TAG_CALIBRATION, "Using high-accuracy model: ${poseDetector.getCurrentModel()}")

        // Load existing calibration data
        viewModelScope.launch {
            calibrationRepository.getCalibrationData().collect { data ->
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
            // Convert ImageProxy to CameraFrame for domain layer
            val cameraFrame = com.lifthelper.vision.adapter.ImageProxyAdapter.toCameraFrame(imageProxy)
            val poseResult = poseDetector.detectPose(cameraFrame)

            if (poseResult != null) {
                // Check calibration quality (full body visible?)
                val quality = positionCalculator.getCalibrationQuality(poseResult, calibrationData)

                // Check camera alignment (including center alignment)
                val aspectRatioResult = coordinateCalculator.calculateAspectRatio(
                    poseResult.imageWidth,
                    poseResult.imageHeight
                )
                val alignmentResult = cameraAlignmentChecker.checkAlignment(
                    poseResult,
                    aspectRatioResult.ratio,
                    aspectRatioResult.isPortrait
                )

                _uiState.update { state ->
                    state.copy(
                        poseResult = poseResult,
                        calibrationQuality = quality,
                        bodyCenterX = alignmentResult.bodyCenterX,
                        alignmentMessage = alignmentResult.message
                    )
                }

                // If buffering (during final 2 seconds), collect frames
                if (_uiState.value.isBuffering) {
                    synchronized(frameBuffer) {
                        frameBuffer.add(poseResult)
                        // Keep only the most recent frames
                        if (frameBuffer.size > maxBufferSize) {
                            frameBuffer.removeAt(0)
                        }
                    }
                    Log.d(TAG_CALIBRATION, "Buffered frame ${frameBuffer.size}/$maxBufferSize")
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

        // Clear previous buffer
        synchronized(frameBuffer) {
            frameBuffer.clear()
        }

        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            _uiState.update { it.copy(isCapturing = true, errorMessage = null) }

            // 10 second countdown for solo front camera mode (time to position yourself)
            for (i in 10 downTo 1) {
                // Start buffering frames during last 2 seconds
                if (i == 2) {
                    _uiState.update { it.copy(isBuffering = true) }
                    Log.i(TAG_CALIBRATION, "Started buffering frames for robust capture")
                }

                _uiState.update { it.copy(captureCountdown = i) }
                delay(1000)
            }

            // Capture measurements from buffered frames
            captureMeasurements()
        }
    }

    /**
     * Capture anthropometric measurements from buffered frames
     * Uses multiple frames for robustness against temporary landmark detection failures
     */
    private suspend fun captureMeasurements() {
        _uiState.update { it.copy(captureCountdown = null, isBuffering = false) }

        // Get buffered frames
        val frames = synchronized(frameBuffer) {
            frameBuffer.toList()
        }

        Log.i(TAG_CALIBRATION, "Processing ${frames.size} buffered frames for measurement")

        if (frames.isEmpty()) {
            Log.w(TAG_CALIBRATION, "Cannot capture: no frames buffered")
            _uiState.update { it.copy(
                isCapturing = false,
                errorMessage = "No pose detected during capture window. Please try again."
            )}
            return
        }

        // Get user height and shoulder width from calibration data
        val userHeight = calibrationData?.userHeight ?: 1.75f
        val userWeight = calibrationData?.anthropometricData?.userWeight
        val manualShoulderWidth = calibrationData?.shoulderWidth

        // Try to get valid measurements from buffered frames
        val bestResult = selectBestFrameForMeasurement(frames, userHeight, userWeight, manualShoulderWidth)

        if (bestResult != null && bestResult.isValid()) {
            Log.i(TAG_CALIBRATION, "✅ Anthropometric data captured successfully from buffered frames")
            Log.d(TAG_CALIBRATION, "Shoulder width: ${(bestResult.getShoulderWidth() * 100).toInt()}cm")
            Log.d(TAG_CALIBRATION, "Torso height: ${(bestResult.getTorsoHeight() * 100).toInt()}cm")
            Log.d(TAG_CALIBRATION, "Leg length: ${((bestResult.getUpperLegHeight() + bestResult.getLowerLegHeight()) * 100).toInt()}cm")

            _uiState.update { state ->
                state.copy(
                    anthropometricData = bestResult,
                    captureComplete = true,
                    isCapturing = false
                )
            }
        } else {
            // Log detailed failure reason
            val errorMsg = if (bestResult == null) {
                Log.e(TAG_CALIBRATION, "❌ Failed to capture: no valid measurements from ${frames.size} frames")
                Log.e(TAG_CALIBRATION, "   Missing critical landmarks (shoulders, hips, or knees) in all frames")
                "Could not detect body landmarks consistently. Ensure shoulders, hips, and knees remain visible."
            } else {
                Log.e(TAG_CALIBRATION, "❌ Failed to capture: data validation failed (unrealistic proportions)")
                Log.e(TAG_CALIBRATION, "   Shoulder width: ${(bestResult.getShoulderWidth() * 100).toInt()}cm")
                Log.e(TAG_CALIBRATION, "   Torso height: ${(bestResult.getTorsoHeight() * 100).toInt()}cm")
                "Measurements appear unrealistic. Try standing further back and more centered."
            }
            _uiState.update { it.copy(isCapturing = false, errorMessage = errorMsg) }
        }

        // Clear buffer
        synchronized(frameBuffer) {
            frameBuffer.clear()
        }
    }

    /**
     * Select the best frame from buffer based on landmark visibility
     * Prioritizes frames with highest visibility of critical joints
     */
    private fun selectBestFrameForMeasurement(
        frames: List<com.lifthelper.domain.model.PoseResult>,
        userHeight: Float,
        userWeight: Float?,
        manualShoulderWidth: Float?
    ): AnthropometricData? {
        // Score each frame based on critical landmark visibility
        val scoredFrames = frames.mapNotNull { frame ->
            val score = calculateFrameQualityScore(frame)
            if (score > 0) {
                Pair(frame, score)
            } else null
        }.sortedByDescending { it.second }

        Log.d(TAG_CALIBRATION, "Frame scores: ${scoredFrames.map { String.format("%.2f", it.second) }}")

        // Try frames in order of quality until we get valid measurements
        for ((frame, score) in scoredFrames) {
            val result = anthropometricDataBuilder.build(
                poseResult = frame,
                userHeight = userHeight,
                userWeight = userWeight,
                manualShoulderWidth = manualShoulderWidth,
                isFrontCamera = cameraManager.isFrontCamera()
            ).getOrNull()

            if (result != null && result.isValid()) {
                Log.i(TAG_CALIBRATION, "Selected frame with quality score: ${String.format("%.2f", score)}")
                return result
            }
        }

        Log.w(TAG_CALIBRATION, "No valid measurements from ${scoredFrames.size} scored frames")
        return null
    }

    /**
     * Calculate quality score for a frame based on critical landmarks
     * Critical landmarks: shoulders (11,12), hips (23,24), knees (25,26)
     * Important: at least one head landmark (0,7,8)
     * Optional: ankles (27,28), elbows (13,14), wrists (15,16)
     */
    private fun calculateFrameQualityScore(frame: com.lifthelper.domain.model.PoseResult): Float {
        val landmarks = frame.landmarks

        // Critical landmarks - must have good visibility
        val criticalIndices = listOf(11, 12, 23, 24, 25, 26)  // shoulders, hips, knees
        val criticalVisibility = criticalIndices.mapNotNull {
            landmarks.getOrNull(it)?.visibility
        }

        if (criticalVisibility.size < criticalIndices.size) {
            return 0f  // Missing critical landmarks
        }

        val criticalAvg = criticalVisibility.average().toFloat()
        if (criticalAvg < 0.5f) {
            return 0f  // Critical landmarks not visible enough
        }

        // Head landmarks - need at least one (nose OR ear)
        val headIndices = listOf(0, 7, 8)  // nose, ears
        val headVisibility = headIndices.mapNotNull {
            landmarks.getOrNull(it)?.visibility
        }.maxOrNull() ?: 0f

        if (headVisibility < 0.5f) {
            return 0f  // No head landmark visible
        }

        // Optional landmarks - bonus for having them
        val ankleIndices = listOf(27, 28)
        val ankleVisibility = ankleIndices.mapNotNull {
            landmarks.getOrNull(it)?.visibility
        }.average().toFloat()

        // Calculate weighted score
        // Critical joints: 70% weight
        // Head: 20% weight
        // Ankles: 10% weight (bonus)
        return (criticalAvg * 0.7f) + (headVisibility * 0.2f) + (ankleVisibility * 0.1f)
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
            calibrationRepository.saveCalibration(updatedCalibration)

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
        synchronized(frameBuffer) {
            frameBuffer.clear()
        }
        _uiState.update { state ->
            state.copy(
                isCapturing = false,
                captureCountdown = null,
                captureComplete = false,
                anthropometricData = null,
                isBuffering = false
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
