package com.lifthelper.ui.screens.workout

import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.data.local.preferences.SettingsDataStore
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.util.Constants.TAG_WORKOUT
import com.lifthelper.vision.calculator.VelocityCalculator
import com.lifthelper.vision.pose.PoseDetector
import com.lifthelper.vision.tracking.MovementPhase
import com.lifthelper.vision.tracking.MovementPhaseDetector
import com.lifthelper.vision.tracking.SquatPhaseDetector
import com.lifthelper.vision.calculator.PositionCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class WorkoutViewModel @Inject constructor(
    private val poseDetector: PoseDetector,
    private val velocityCalculator: VelocityCalculator,
    private val phaseDetector: MovementPhaseDetector,
    private val squatPhaseDetector: SquatPhaseDetector,
    private val positionCalculator: PositionCalculator,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkoutState())
    val uiState: StateFlow<WorkoutState> = _uiState.asStateFlow()

    private var calibrationData: CalibrationData? = null
    private var lastPhase = MovementPhase.IDLE
    private var repCount = 0

    private var noPoseFrameCount = 0
    private var lastNoPoseLogTime = 0L

    init {
        Log.d(TAG_WORKOUT, "WorkoutViewModel initialized")
        // Load calibration data
        viewModelScope.launch {
            settingsDataStore.calibrationData.collect { calibration ->
                calibrationData = calibration
                Log.i(TAG_WORKOUT, "Calibration data loaded: pixelsPerMeter=${calibration?.pixelsPerMeter}, isCalibrated=${calibration != null}")
            }
        }
    }

    /**
     * Process camera frame - called by CameraX analyzer
     */
    fun processFrame(imageProxy: ImageProxy) {
        val timestamp = System.currentTimeMillis()

        try {
            // 1. Detect pose
            val poseResult = poseDetector.detectPose(imageProxy, timestamp)

            if (poseResult != null && calibrationData != null) {
                // 2. Calculate velocity
                val velocityData = velocityCalculator.calculate(
                    poseResult = poseResult,
                    exerciseType = _uiState.value.selectedExercise,
                    calibrationData = calibrationData!!,
                    timestamp = timestamp
                )

                // 3. Detect movement phase and count reps (only when recording)
                if (_uiState.value.isRecording && calibrationData != null) {
                    // Get body part positions for squat detection
                    val bodyPartPositions = positionCalculator.getBodyPartPositions(poseResult)

                    if (bodyPartPositions != null) {
                        // Convert pixel positions to meters
                        val headMeters = bodyPartPositions.headY?.let {
                            positionCalculator.pixelsToMeters(it, poseResult, calibrationData!!)
                        }
                        val shouldersMeters = bodyPartPositions.shouldersY?.let {
                            positionCalculator.pixelsToMeters(it, poseResult, calibrationData!!)
                        }
                        val hipsMeters = bodyPartPositions.hipsY?.let {
                            positionCalculator.pixelsToMeters(it, poseResult, calibrationData!!)
                        }
                        val kneesMeters = bodyPartPositions.kneesY?.let {
                            positionCalculator.pixelsToMeters(it, poseResult, calibrationData!!)
                        }

                        // Use position-based squat detection
                        val currentPhase = squatPhaseDetector.detectPhase(
                            headY = headMeters,
                            shouldersY = shouldersMeters,
                            hipsY = hipsMeters,
                            kneesY = kneesMeters,
                            poseResult = poseResult
                        )

                        // Count rep when transitioning from CONCENTRIC to TOP
                        if (lastPhase == MovementPhase.CONCENTRIC && currentPhase == MovementPhase.TOP) {
                            repCount++
                            val posInfo = squatPhaseDetector.getPositionInfo()
                            Log.i(TAG_WORKOUT, "✅ Rep completed: $repCount | $posInfo")
                            Timber.d("Rep completed: $repCount")
                        }

                        // Log phase transitions
                        if (lastPhase != currentPhase) {
                            Log.d(TAG_WORKOUT, "Phase transition: $lastPhase -> $currentPhase")
                        }

                        lastPhase = currentPhase
                    }
                }

                // 4. Check calibration quality
                val calibrationQuality = positionCalculator.getCalibrationQuality(poseResult, calibrationData)

                // 5. Update UI state
                _uiState.update { state ->
                    state.copy(
                        poseResult = poseResult,
                        velocityData = velocityData,
                        repCount = repCount,
                        calibrationQuality = calibrationQuality
                    )
                }
            } else {
                // Log "no pose" warning only once every 2 seconds to avoid spam
                if (poseResult == null) {
                    noPoseFrameCount++
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastNoPoseLogTime > 2000) {
                        Log.w(TAG_WORKOUT, "No pose detected (missed $noPoseFrameCount frames in last 2s)")
                        noPoseFrameCount = 0
                        lastNoPoseLogTime = currentTime
                    }
                }
                if (calibrationData == null && System.currentTimeMillis() % 5000 < 50) {
                    Log.w(TAG_WORKOUT, "No calibration data available")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG_WORKOUT, "❌ Frame processing error", e)
            Timber.e(e, "Frame processing error")
        } finally {
            imageProxy.close()
        }
    }

    fun startRecording() {
        Log.i(TAG_WORKOUT, "▶️ START RECORDING - Exercise: ${_uiState.value.selectedExercise}, Weight: ${_uiState.value.weight}kg")
        repCount = 0
        noPoseFrameCount = 0
        lastNoPoseLogTime = 0L
        velocityCalculator.reset()
        phaseDetector.reset()
        squatPhaseDetector.reset()
        lastPhase = MovementPhase.IDLE
        _uiState.update { it.copy(isRecording = true, repCount = 0) }
        Log.d(TAG_WORKOUT, "Recording state updated, calculators reset")
    }

    fun stopRecording() {
        Log.i(TAG_WORKOUT, "⏹️ STOP RECORDING - Total reps: $repCount")
        _uiState.update { it.copy(isRecording = false) }
    }

    fun setWeight(weight: Float) {
        _uiState.update { it.copy(weight = weight) }
    }

    fun setTargetReps(reps: Int) {
        _uiState.update { it.copy(targetReps = reps) }
    }

    fun setExercise(exercise: com.lifthelper.domain.model.ExerciseType) {
        Log.i(TAG_WORKOUT, "Exercise changed to: $exercise")
        _uiState.update { it.copy(selectedExercise = exercise) }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG_WORKOUT, "WorkoutViewModel cleared, closing pose detector")
        poseDetector.close()
    }
}
