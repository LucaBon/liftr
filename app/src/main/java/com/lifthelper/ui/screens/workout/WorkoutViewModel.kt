package com.lifthelper.ui.screens.workout

import androidx.camera.core.ImageProxy
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.domain.exercise.BarPathAnalyzer
import com.lifthelper.domain.exercise.ExercisePhaseState
import com.lifthelper.domain.exercise.FormIssue
import com.lifthelper.domain.exercise.RepQualityScorer
import com.lifthelper.domain.exercise.TempoAdherence
import com.lifthelper.domain.exercise.TempoCue
import com.lifthelper.domain.exercise.TempoState
import com.lifthelper.domain.exercise.TempoTracker
import com.lifthelper.domain.exercise.VelocityLossState
import com.lifthelper.domain.exercise.VelocityLossTracker
import com.lifthelper.domain.feedback.IFeedbackEmitter
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.AggregateFormIssue
import com.lifthelper.domain.model.BarPathData
import com.lifthelper.domain.model.BarPathPoint
import com.lifthelper.domain.model.TempoPrescription
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.DepthValidationResult
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.FeedbackEvent
import com.lifthelper.domain.model.PoseKeypoint
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.Rep
import com.lifthelper.domain.model.RepQualityScore
import com.lifthelper.domain.model.Set
import com.lifthelper.domain.model.SetSummary
import com.lifthelper.domain.model.VbtZone
import com.lifthelper.domain.model.Workout
import com.lifthelper.domain.repository.CalibrationRepository
import com.lifthelper.domain.repository.SettingsKeys
import com.lifthelper.domain.repository.SettingsRepository
import com.lifthelper.domain.usecase.AnalyzeRepUseCase
import com.lifthelper.domain.usecase.ProcessWorkoutFrameUseCase
import com.lifthelper.domain.usecase.FrameProcessingResult
import com.lifthelper.domain.usecase.CheckPersonalRecordsUseCase
import com.lifthelper.domain.usecase.LogManualSetUseCase
import com.lifthelper.domain.usecase.SaveWorkoutUseCase
import com.lifthelper.domain.repository.CustomExerciseRepository
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.value.RepCount
import com.lifthelper.ui.common.UiError
import com.lifthelper.ui.common.toUiError
import com.lifthelper.vision.tracking.MovementPhase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@HiltViewModel
class WorkoutViewModel @Inject constructor(
    private val processFrameUseCase: ProcessWorkoutFrameUseCase,
    private val analyzeRepUseCase: AnalyzeRepUseCase,
    private val saveWorkoutUseCase: SaveWorkoutUseCase,
    private val checkPersonalRecordsUseCase: CheckPersonalRecordsUseCase,
    private val logManualSetUseCase: LogManualSetUseCase,
    private val customExerciseRepository: CustomExerciseRepository,
    private val calibrationRepository: CalibrationRepository,
    private val settingsRepository: SettingsRepository,
    private val feedbackEmitter: IFeedbackEmitter,
    private val logger: Logger,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "WorkoutViewModel"
        private const val KEY_EXERCISE = "exercise_type"
        private const val KEY_WEIGHT = "weight"
        private const val KEY_TARGET_REPS = "target_reps"
    }

    // IMPROVE-7: Restore user selections after process death
    private val _uiState = MutableStateFlow(WorkoutState(
        selectedExercise = savedStateHandle.get<String>(KEY_EXERCISE)
            ?.let { name -> ExerciseType.entries.find { it.name == name } }
            ?: ExerciseType.FRONT_SQUAT,
        weight = Kilograms(savedStateHandle.get<Float>(KEY_WEIGHT) ?: 0f),
        targetReps = RepCount(savedStateHandle.get<Int>(KEY_TARGET_REPS) ?: 5)
    ))
    val uiState: StateFlow<WorkoutState> = _uiState.asStateFlow()

    private var calibrationData: CalibrationData? = null
    private var exerciseState = ExercisePhaseState.initial()  // Immutable state for thread safety

    // VBT settings loaded from repository
    @Volatile private var velocityLossThreshold = VelocityLossState.DEFAULT_THRESHOLD
    @Volatile private var vbtZonesEnabled = true

    private var noPoseFrameCount = 0
    // Thread-safe: read/written from coroutines (BUG-G fix)
    private val lastNoPoseLogTime = AtomicLong(0L)

    // IMPROVE-1: AtomicReference holds latest frame. Old frames are closed immediately
    // when replaced, preventing memory buildup on slow devices.
    private val latestFrame = java.util.concurrent.atomic.AtomicReference<ImageProxy?>(null)
    @Volatile private var isProcessingFrame = false

    // Track previous rep count to detect new reps
    private var previousRepCount = 0

    // --- Bar Path: real-time state for overlay ---
    private val _currentBarPathPoints = MutableStateFlow<List<BarPathPoint>>(emptyList())
    val currentBarPathPoints: StateFlow<List<BarPathPoint>> = _currentBarPathPoints.asStateFlow()

    // --- Rep Quality Scoring: per-rep accumulators ---
    private var currentRepBarPathPoints = mutableListOf<BarPathPoint>()
    private var currentRepFormIssues = mutableListOf<FormIssue>()
    // Buffered frames for the in-progress rep. Drained into AnalyzeRepUseCase
    // on rep completion so the heavy form/metric work never runs per-frame.
    private var currentRepPoses = mutableListOf<PoseResult>()
    private var currentRepEccentricStartTime: Long = 0L
    private var currentRepBottomTime: Long = 0L
    private var currentRepDepthValidation: DepthValidationResult? = null
    private var previousPhase: MovementPhase = MovementPhase.IDLE

    // --- Auto-stop on target reps ---
    @Volatile private var autoStopOnTargetReps = true
    private var autoStopFiredForCurrentSet = false

    // --- Rep Quality Scoring: per-set accumulators ---
    private var setRepScores = mutableListOf<RepQualityScore>()
    private var setRepVelocities = mutableListOf<MetersPerSecond>()
    private var previousConcentricDurations = mutableListOf<Float>()

    // --- Bar Path: per-set analyzed paths ---
    private var setBarPaths = mutableListOf<BarPathData>()

    // --- Per-set Rep domain objects for persistence ---
    private var setReps = mutableListOf<Rep>()

    // --- Session-level accumulators for workout persistence ---
    private val completedSets = mutableListOf<Set>()
    private var workoutStartTime: Long = 0L

    // --- Tempo Training ---
    private var tempoState: TempoState? = null
    @Volatile private var tempoModeEnabled = false
    private var lastTempoCountdownSecond = -1

    // --- Rest Timer ---
    private var restTimerJob: Job? = null
    @Volatile private var restTimerEnabled = true

    init {
        logger.d(TAG, "WorkoutViewModel initialized")
        viewModelScope.launch {
            calibrationRepository.getCalibrationData().collect { calibration ->
                calibrationData = calibration
                logger.i(TAG, "Calibration data loaded: pixelsPerMeter=${calibration?.pixelsPerMeter}, isCalibrated=${calibration != null}")
            }
        }
        loadVbtSettings()
        loadFeedbackSettings()
        loadRestTimerSettings()
        loadTempoSettings()
        loadAutoStopSettings()
        loadCustomExerciseNames()
    }

    private fun loadAutoStopSettings() {
        viewModelScope.launch {
            settingsRepository.getBooleanSetting(
                SettingsKeys.AUTO_STOP_ON_TARGET_REPS,
                true
            ).collect { enabled ->
                autoStopOnTargetReps = enabled
            }
        }
    }

    private fun loadVbtSettings() {
        viewModelScope.launch {
            settingsRepository.getBooleanSetting(
                SettingsKeys.VBT_ZONES_ENABLED,
                true
            ).collect { enabled ->
                vbtZonesEnabled = enabled
                _uiState.update { it.copy(vbtZonesEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getFloatSetting(
                SettingsKeys.VELOCITY_LOSS_THRESHOLD,
                VelocityLossState.DEFAULT_THRESHOLD
            ).collect { threshold ->
                velocityLossThreshold = threshold
                // Update the current loss state's threshold if not mid-set
                if (!_uiState.value.isRecording) {
                    _uiState.update {
                        it.copy(velocityLossState = VelocityLossTracker.reset(threshold))
                    }
                }
            }
        }
    }

    private fun loadFeedbackSettings() {
        viewModelScope.launch {
            settingsRepository.getBooleanSetting(
                SettingsKeys.AUDIO_FEEDBACK_ENABLED,
                true
            ).collect { enabled ->
                feedbackEmitter.setAudioEnabled(enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.getBooleanSetting(
                SettingsKeys.HAPTIC_FEEDBACK_ENABLED,
                true
            ).collect { enabled ->
                feedbackEmitter.setHapticEnabled(enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.getStringSetting(
                SettingsKeys.FEEDBACK_LANGUAGE,
                com.lifthelper.domain.feedback.FeedbackLanguage.DEFAULT.name
            ).collect { name ->
                feedbackEmitter.setLanguage(
                    com.lifthelper.domain.feedback.FeedbackLanguage.fromString(name)
                )
            }
        }
        viewModelScope.launch {
            settingsRepository.getStringSetting(
                SettingsKeys.FEEDBACK_VERBOSITY,
                com.lifthelper.domain.feedback.FeedbackVerbosity.DEFAULT.name
            ).collect { name ->
                feedbackEmitter.setVerbosity(
                    com.lifthelper.domain.feedback.FeedbackVerbosity.fromString(name)
                )
            }
        }
        viewModelScope.launch {
            settingsRepository.getStringSetting(
                SettingsKeys.FEEDBACK_TTS_ENGINE,
                ""
            ).collect { pkg ->
                feedbackEmitter.setTtsEngine(pkg.takeIf { it.isNotBlank() })
            }
        }
    }

    /**
     * Process camera frame with backpressure (IMPROVE-1).
     * If processing is slower than the camera frame rate, intermediate frames
     * are dropped and their ImageProxy closed immediately to free camera buffers.
     */
    fun processFrame(imageProxy: ImageProxy) {
        // Replace the latest frame, closing the old one if it wasn't processed yet
        val oldFrame = latestFrame.getAndSet(imageProxy)
        oldFrame?.close()

        // Only launch processing if not already running
        if (!isProcessingFrame) {
            isProcessingFrame = true
            viewModelScope.launch {
                try {
                    // Drain: take the latest frame for processing
                    val frame = latestFrame.getAndSet(null) ?: return@launch
                    val result = processFrameUseCase(
                        ProcessWorkoutFrameUseCase.Params(
                            imageProxy = frame,
                            timestamp = System.currentTimeMillis(),
                            calibrationData = calibrationData,
                            exerciseType = _uiState.value.selectedExercise,
                            isRecording = _uiState.value.isRecording,
                            exerciseState = exerciseState,
                            currentRepCount = _uiState.value.repCount.value,
                            weight = _uiState.value.weight
                        )
                    )
                    handleFrameProcessingResult(result)
                } finally {
                    isProcessingFrame = false
                }
            }
        }
    }

    private fun handleFrameProcessingResult(result: FrameProcessingResult) {
        when (result) {
            is FrameProcessingResult.Success -> {
                // Update exercise state with new immutable state from phase detection
                result.phaseResult?.let { phaseResult ->
                    exerciseState = phaseResult.newState
                }

                val newRepCount = result.phaseResult?.repCount ?: _uiState.value.repCount.value
                val repJustCounted = result.phaseResult?.repCounted == true
                val newPhase = result.phaseResult?.newState?.currentPhase ?: previousPhase

                // Rep quality: collect data and detect phase transitions
                if (_uiState.value.isRecording) {
                    collectRepQualityData(result, newPhase, repJustCounted)
                }

                _uiState.update { state ->
                    var updatedState = state.copy(
                        poseResult = result.poseResult,
                        velocityData = result.velocityData,
                        repCount = RepCount(newRepCount),
                        calibrationQuality = result.calibrationQuality,
                        error = null
                    )

                    // VBT: Update zone and velocity loss on rep completion
                    if (repJustCounted && result.velocityData != null) {
                        val repAvgVelocity = result.velocityData.average

                        // Determine VBT zone
                        if (vbtZonesEnabled) {
                            updatedState = updatedState.copy(
                                vbtZone = VbtZone.fromVelocity(repAvgVelocity)
                            )
                        }

                        // Update velocity loss tracking
                        val newLossState = VelocityLossTracker.update(
                            state.velocityLossState, repAvgVelocity
                        )
                        updatedState = updatedState.copy(velocityLossState = newLossState)

                        if (newLossState.shouldStop) {
                            logger.i(TAG, "VBT: Velocity loss threshold exceeded " +
                                "(${(newLossState.currentLossPercent * 100).toInt()}% > " +
                                "${(newLossState.threshold * 100).toInt()}%)")
                        }
                    }

                    updatedState
                }

                // Feedback: emit events after state update (only when recording)
                if (_uiState.value.isRecording) {
                    emitFeedbackEvents(result, repJustCounted, newRepCount)
                }

                previousRepCount = newRepCount
                previousPhase = newPhase
            }

            is FrameProcessingResult.NoPoseDetected -> {
                noPoseFrameCount++
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastNoPoseLogTime.get() > 2000) {
                    logger.w(TAG, "No pose detected (missed $noPoseFrameCount frames in last 2s)")
                    noPoseFrameCount = 0
                    lastNoPoseLogTime.set(currentTime)
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
     * Collects per-rep quality data during recording.
     * Detects phase transitions and accumulates bar X positions and form issues.
     */
    private fun collectRepQualityData(
        result: FrameProcessingResult.Success,
        newPhase: MovementPhase,
        repJustCounted: Boolean
    ) {
        val now = System.currentTimeMillis()

        // Detect phase transitions
        if (newPhase != previousPhase) {
            when (newPhase) {
                MovementPhase.ECCENTRIC -> {
                    // New rep starting: reset per-rep accumulators
                    currentRepBarPathPoints.clear()
                    _currentBarPathPoints.value = emptyList()
                    currentRepFormIssues.clear()
                    currentRepPoses.clear()
                    currentRepDepthValidation = null
                    currentRepEccentricStartTime = now
                    currentRepBottomTime = 0L
                    // Clear the depth-not-reached flash once the lifter is
                    // descending into the next rep.
                    if (_uiState.value.lastRepDepthOk != null) {
                        _uiState.update { it.copy(lastRepDepthOk = null) }
                    }
                    // Reset tempo state for new rep
                    tempoState?.let { ts ->
                        tempoState = TempoTracker.reset(ts.prescription)
                        tempoState = TempoTracker.onPhaseTransition(tempoState!!, newPhase, now)
                        lastTempoCountdownSecond = -1
                    }
                }
                MovementPhase.BOTTOM -> {
                    currentRepBottomTime = now
                    // Store depth validation from phase result
                    result.phaseResult?.depthValidation?.let {
                        currentRepDepthValidation = it
                    }
                    tempoState?.let { ts ->
                        tempoState = TempoTracker.onPhaseTransition(ts, newPhase, now)
                    }
                }
                else -> {
                    // Update tempo state for CONCENTRIC and TOP transitions
                    tempoState?.let { ts ->
                        tempoState = TempoTracker.onPhaseTransition(ts, newPhase, now)
                    }
                }
            }
        }

        // Emit tempo cues every frame during recording
        tempoState?.let { ts ->
            val cue = TempoTracker.getCue(ts, now)
            _uiState.update { it.copy(tempoState = ts, tempoCue = cue) }
            emitTempoCueFeedback(cue, ts, now)
        }

        // Collect bar path position + buffer pose frames during active rep phases.
        // Form validation used to run per-frame here; it's now deferred to
        // AnalyzeRepUseCase at rep-completion time so the hot path stays light.
        if (newPhase in listOf(MovementPhase.ECCENTRIC, MovementPhase.BOTTOM, MovementPhase.CONCENTRIC)) {
            extractBarPathPoint(result.poseResult, result.velocityData, _uiState.value.selectedExercise)?.let {
                currentRepBarPathPoints.add(it)
                _currentBarPathPoints.value = currentRepBarPathPoints.toList()
            }
            currentRepPoses.add(result.poseResult)
        }

        // Score the rep when it completes
        if (repJustCounted) {
            scoreCurrentRep(result, now)
        }
    }

    /**
     * Scores the just-completed rep and adds it to the set accumulators.
     * Also builds a Rep domain object for persistence.
     *
     * This is where the deferred form validation + metric calculation runs:
     * we replay the buffered poses through [AnalyzeRepUseCase] once per rep
     * instead of doing it every frame on the camera hot path.
     */
    private fun scoreCurrentRep(result: FrameProcessingResult.Success, now: Long) {
        val eccentricDuration = if (currentRepBottomTime > 0 && currentRepEccentricStartTime > 0) {
            (currentRepBottomTime - currentRepEccentricStartTime) / 1000f
        } else 0f

        val concentricDuration = if (currentRepBottomTime > 0) {
            (now - currentRepBottomTime) / 1000f
        } else 0f

        // Calculate tempo adherence if in tempo mode
        val tempoAdherence: TempoAdherence? = tempoState?.let { ts ->
            // Finalize the concentric phase duration before scoring
            val finalizedState = TempoTracker.onPhaseTransition(ts, MovementPhase.IDLE, now)
            tempoState = TempoTracker.reset(ts.prescription)
            TempoTracker.calculateAdherence(finalizedState)
        }

        // Deferred heavy analysis over buffered rep frames.
        val calib = calibrationData
        val analysis: AnalyzeRepUseCase.Result? = if (calib != null && currentRepPoses.isNotEmpty()) {
            runCatching {
                analyzeRepUseCase(
                    AnalyzeRepUseCase.Params(
                        exerciseType = _uiState.value.selectedExercise,
                        calibrationData = calib,
                        weight = _uiState.value.weight,
                        bufferedPoses = currentRepPoses.toList(),
                        finalVelocityData = result.velocityData
                    )
                )
            }.onFailure { logger.w(TAG, "AnalyzeRepUseCase failed: ${it.message}") }
                .getOrNull()
        } else null

        val analyzedFormIssues = analysis?.formValidation?.issues ?: emptyList()
        currentRepFormIssues.clear()
        currentRepFormIssues.addAll(analyzedFormIssues)

        val score = RepQualityScorer.scoreRep(
            exerciseType = _uiState.value.selectedExercise,
            depthValidation = currentRepDepthValidation,
            barXPositions = currentRepBarPathPoints.map { it.normalizedX },
            eccentricDuration = eccentricDuration,
            concentricDuration = concentricDuration,
            previousConcentricDurations = previousConcentricDurations.toList(),
            formIssues = analyzedFormIssues,
            tempoAdherence = tempoAdherence
        )

        // Analyze bar path for this rep
        val barPathData = BarPathAnalyzer.analyze(
            repNumber = setRepScores.size + 1,
            points = currentRepBarPathPoints.toList()
        )
        setBarPaths.add(barPathData)

        setRepScores.add(score)
        previousConcentricDurations.add(concentricDuration)

        // Track per-rep velocity
        val repAvgVelocity = result.velocityData?.average ?: MetersPerSecond(0f)
        val repPeakVelocity = result.velocityData?.instantaneous ?: MetersPerSecond(0f)
        result.velocityData?.let { setRepVelocities.add(it.average) }

        // Build Rep domain object for persistence
        val rep = Rep(
            setId = 0, // assigned during persistence
            repNumber = setRepScores.size,
            peakVelocity = repPeakVelocity,
            avgVelocity = repAvgVelocity,
            timeUnderTension = eccentricDuration + concentricDuration,
            rangeOfMotion = Meters(0f),
            startPosition = Meters(0f),
            endPosition = Meters(0f),
            eccentricDuration = eccentricDuration,
            concentricDuration = concentricDuration,
            velocityProfile = emptyList(),
            positionProfile = emptyList(),
            timestamp = now,
            qualityScore = score.totalScore
        )
        setReps.add(rep)

        logger.i(TAG, "Rep ${setRepScores.size} scored: ${score.totalScore}/100 " +
            "(depth=${score.depthScore}, barPath=${score.barPathScore}, " +
            "tempo=${score.tempoScore}, form=${score.formScore}) " +
            "grade=${score.grade.label}")

        // Update UI with latest rep score and depth cue (null when depth
        // validation wasn't applicable — e.g. non-squat lifts).
        val depthOk = currentRepDepthValidation?.isValid
        _uiState.update { it.copy(currentRepScore = score, lastRepDepthOk = depthOk) }
    }

    /**
     * Extracts a full bar path point from the pose result and velocity data.
     * The tracked body part depends on the exercise type.
     */
    private fun extractBarPathPoint(
        poseResult: PoseResult,
        velocityData: com.lifthelper.domain.model.VelocityData?,
        exerciseType: ExerciseType
    ): BarPathPoint? {
        val landmarks = poseResult.landmarks
        val (nx, ny) = when (exerciseType) {
            ExerciseType.BACK_SQUAT, ExerciseType.FRONT_SQUAT -> {
                val left = landmarks.getOrNull(PoseResult.LEFT_SHOULDER)
                val right = landmarks.getOrNull(PoseResult.RIGHT_SHOULDER)
                averageXY(left, right) ?: return null
            }
            ExerciseType.BENCH_PRESS, ExerciseType.DEADLIFT -> {
                val left = landmarks.getOrNull(PoseResult.LEFT_WRIST)
                val right = landmarks.getOrNull(PoseResult.RIGHT_WRIST)
                averageXY(left, right) ?: return null
            }
            else -> return null
        }

        val ppm = calibrationData?.pixelsPerMeter ?: 1f
        val imageWidth = poseResult.imageWidth.toFloat()
        val imageHeight = poseResult.imageHeight.toFloat()

        return BarPathPoint(
            normalizedX = nx,
            normalizedY = ny,
            realWorldXCm = (nx * imageWidth / ppm) * 100f,
            realWorldYCm = (ny * imageHeight / ppm) * 100f,
            velocity = velocityData?.instantaneous ?: MetersPerSecond(0f),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun averageXY(a: PoseKeypoint?, b: PoseKeypoint?): Pair<Float, Float>? {
        if (a == null || b == null) return null
        if (a.visibility < 0.5f || b.visibility < 0.5f) return null
        return Pair((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    }

    /**
     * Clears the current error state.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun startRecording() {
        // Initialize workout start time on first set
        if (completedSets.isEmpty()) {
            workoutStartTime = System.currentTimeMillis()
        }

        logger.i(TAG, "START RECORDING - Exercise: ${_uiState.value.selectedExercise}, Weight: ${_uiState.value.weight.value}kg")
        noPoseFrameCount = 0
        lastNoPoseLogTime.set(0L)
        previousRepCount = 0
        exerciseState = ExercisePhaseState.initial()  // Reset to fresh state

        // Reset rep quality scoring accumulators
        currentRepBarPathPoints.clear()
        _currentBarPathPoints.value = emptyList()
        currentRepFormIssues.clear()
        currentRepPoses.clear()
        currentRepDepthValidation = null
        currentRepEccentricStartTime = 0L
        currentRepBottomTime = 0L
        setRepScores.clear()
        setRepVelocities.clear()
        previousConcentricDurations.clear()
        setBarPaths.clear()
        setReps.clear()
        previousPhase = MovementPhase.IDLE
        autoStopFiredForCurrentSet = false

        // Initialize tempo tracking if enabled
        val prescription = _uiState.value.tempoPrescription
        if (tempoModeEnabled && prescription != null) {
            tempoState = TempoTracker.reset(prescription)
            lastTempoCountdownSecond = -1
        } else {
            tempoState = null
        }

        _uiState.update {
            it.copy(
                isRecording = true,
                repCount = RepCount(0),
                error = null,
                vbtZone = null,
                velocityLossState = VelocityLossTracker.reset(velocityLossThreshold),
                velocityLossAlertDismissed = false,
                currentRepScore = null,
                lastSetSummary = null,
                tempoState = tempoState,
                tempoCue = null
            )
        }
        logger.d(TAG, "Recording state updated, exercise state reset, VBT loss tracker reset")
    }

    private fun emitFeedbackEvents(
        result: FrameProcessingResult.Success,
        repJustCounted: Boolean,
        newRepCount: Int
    ) {
        // Rep counted
        if (repJustCounted) {
            feedbackEmitter.emit(FeedbackEvent.RepCounted(newRepCount))

            // Depth-not-reached cue (only real-time form cue that survived the
            // per-frame strip — it's robust and critical to rep validity).
            val depth = currentRepDepthValidation
            if (depth != null && !depth.isValid) {
                feedbackEmitter.emit(FeedbackEvent.DepthInsufficient)
            }

            // Check if target reps reached → emit SetComplete and, if enabled,
            // auto-stop the set exactly once.
            val targetReps = _uiState.value.targetReps.value
            if (targetReps > 0 && newRepCount >= targetReps) {
                feedbackEmitter.emit(FeedbackEvent.SetComplete)
                if (autoStopOnTargetReps && !autoStopFiredForCurrentSet) {
                    autoStopFiredForCurrentSet = true
                    logger.i(TAG, "Auto-stop: target reps ($targetReps) reached, ending set")
                    stopRecording()
                }
            }
        }

        // Velocity loss alert
        val lossState = _uiState.value.velocityLossState
        if (repJustCounted && lossState.shouldStop) {
            feedbackEmitter.emit(FeedbackEvent.VelocityLossAlert(lossState.currentLossPercent))
        }

        // Per-frame form warnings/critical alerts were removed from the hot
        // path: they're prone to false positives (noisy angle thresholds) and
        // now come from the deferred analysis summary shown during rest.
    }

    /**
     * Emits audio/haptic feedback for tempo cues. Debounces countdown to once per second.
     */
    private fun emitTempoCueFeedback(cue: TempoCue, state: TempoState, now: Long) {
        when (cue) {
            is TempoCue.Countdown -> {
                val secondsLeft = cue.remainingSeconds.toInt()
                if (secondsLeft != lastTempoCountdownSecond && secondsLeft > 0) {
                    lastTempoCountdownSecond = secondsLeft
                    if (cue.phase == MovementPhase.BOTTOM || cue.phase == MovementPhase.TOP) {
                        feedbackEmitter.emit(FeedbackEvent.TempoHold(secondsLeft))
                    } else {
                        feedbackEmitter.emit(FeedbackEvent.TempoCountdown(secondsLeft, cue.phase))
                    }
                }
            }
            is TempoCue.TooFast -> {
                feedbackEmitter.emit(FeedbackEvent.TempoSpeedCue(isTooFast = true))
            }
            is TempoCue.TooSlow -> {
                feedbackEmitter.emit(FeedbackEvent.TempoSpeedCue(isTooFast = false))
            }
            is TempoCue.OnPace, is TempoCue.Explosive, is TempoCue.None -> {
                // No audio feedback needed
            }
        }
    }

    fun stopRecording() {
        logger.i(TAG, "STOP RECORDING - Total reps: ${_uiState.value.repCount}")

        val summary = generateSetSummary()

        // Build Set domain object and accumulate for workout persistence
        if (summary != null) {
            val state = _uiState.value
            val completedSet = Set(
                workoutId = 0, // assigned during persistence
                exerciseType = state.selectedExercise,
                weight = state.weight,
                targetReps = state.targetReps,
                completedReps = RepCount(setRepScores.size),
                avgVelocity = summary.avgVelocity,
                peakVelocity = summary.peakVelocity,
                timestamp = System.currentTimeMillis(),
                reps = setReps.toList()
            )
            completedSets.add(completedSet)
            logger.i(TAG, "Set accumulated for persistence (${completedSets.size} sets in session)")

            // Check for personal records
            checkForPersonalRecords(completedSet)
        }

        // Clear real-time bar path overlay
        _currentBarPathPoints.value = emptyList()

        _uiState.update {
            it.copy(
                isRecording = false,
                lastSetSummary = summary,
                completedSetCount = completedSets.size,
                velocityLossAlertDismissed = false
            )
        }

        if (summary != null) {
            logger.i(TAG, "Set summary: overall=${summary.overallScore}/100 " +
                "(${summary.overallGrade.label}), reps=${summary.completedReps.value}")
        }

        // Auto-start rest timer after tracked set if enabled
        if (restTimerEnabled && summary != null) {
            startRestTimer()
        }
    }

    /**
     * Dismisses the velocity-loss stop alert for the remainder of the current set.
     * Feedback events (haptic/audio) still fire on threshold breach — this only
     * hides the visual banner so the lifter can complete the set unobstructed.
     */
    fun dismissVelocityLossAlert() {
        _uiState.update { it.copy(velocityLossAlertDismissed = true) }
    }

    /**
     * Saves the current workout session to the database.
     * Creates a Workout from all accumulated sets and persists it.
     */
    fun saveWorkout() {
        if (completedSets.isEmpty()) {
            logger.w(TAG, "No sets to save")
            return
        }

        viewModelScope.launch {
            val workout = Workout(
                date = Date(workoutStartTime),
                duration = System.currentTimeMillis() - workoutStartTime,
                sets = completedSets.toList()
            )

            val result = saveWorkoutUseCase(SaveWorkoutUseCase.Params(workout))
            result.onSuccess { id ->
                logger.i(TAG, "Workout saved: id=$id, sets=${completedSets.size}")
                completedSets.clear()
                workoutStartTime = 0L
                _uiState.update { it.copy(workoutSaved = true, completedSetCount = 0) }
            }.onFailure { error ->
                logger.e(TAG, "Failed to save workout: ${error.message}", error)
            }
        }
    }

    /**
     * Checks if the just-completed set breaks any personal records.
     */
    private fun checkForPersonalRecords(set: Set) {
        viewModelScope.launch {
            try {
                // Calculate session tonnage so far
                val sessionTonnage = completedSets.sumOf {
                    (it.weight.value * it.completedReps.value).toDouble()
                }.toFloat()

                val newPRs = checkPersonalRecordsUseCase(
                    CheckPersonalRecordsUseCase.Params(
                        exerciseType = set.exerciseType,
                        set = set,
                        sessionTonnage = sessionTonnage
                    )
                )

                if (newPRs.isNotEmpty()) {
                    logger.i(TAG, "New PRs detected: ${newPRs.map { it.type.displayName }}")
                    _uiState.update { it.copy(newPersonalRecords = newPRs) }
                    feedbackEmitter.emit(FeedbackEvent.PersonalRecord)
                }
            } catch (e: Exception) {
                logger.e(TAG, "Error checking personal records: ${e.message}", e)
            }
        }
    }

    /** Returns true if there are unsaved completed sets */
    fun hasUnsavedSets(): Boolean = completedSets.isNotEmpty()

    /**
     * Generates a SetSummary from accumulated per-rep data.
     * Returns null if no reps were completed.
     */
    private fun generateSetSummary(): SetSummary? {
        if (setRepScores.isEmpty()) return null

        val state = _uiState.value
        val velocities = setRepVelocities.toList()
        val scores = setRepScores.toList()

        val avgVelocity = if (velocities.isNotEmpty()) {
            MetersPerSecond(velocities.map { it.value }.average().toFloat())
        } else MetersPerSecond(0f)

        val peakVelocity = if (velocities.isNotEmpty()) {
            velocities.maxBy { it.value }
        } else MetersPerSecond(0f)

        return SetSummary(
            exerciseType = state.selectedExercise,
            weight = state.weight,
            targetReps = state.targetReps,
            completedReps = RepCount(scores.size),
            repScores = scores,
            repVelocities = velocities,
            avgVelocity = avgVelocity,
            peakVelocity = peakVelocity,
            velocityLossPercent = state.velocityLossState.currentLossPercent,
            overallScore = scores.map { it.totalScore }.average().toInt(),
            bestRepIndex = scores.indices.maxByOrNull { scores[it].totalScore } ?: 0,
            worstRepIndex = scores.indices.minByOrNull { scores[it].totalScore } ?: 0,
            aggregateFormIssues = aggregateFormIssues(scores),
            repBarPaths = setBarPaths.toList()
        )
    }

    /**
     * Aggregates form issues across all reps, grouping by issue message
     * and listing which rep numbers each issue appeared in.
     */
    private fun aggregateFormIssues(scores: List<RepQualityScore>): List<AggregateFormIssue> {
        val issueMap = mutableMapOf<String, Pair<FormIssue, MutableList<Int>>>()
        scores.forEachIndexed { index, score ->
            score.formIssues.forEach { issue ->
                val entry = issueMap.getOrPut(issue.message) { issue to mutableListOf() }
                entry.second.add(index + 1) // 1-indexed rep numbers
            }
        }
        return issueMap.values.map { (issue, repNumbers) ->
            AggregateFormIssue(issue = issue, repNumbers = repNumbers)
        }
    }

    /**
     * Dismisses the post-set summary sheet.
     */
    fun dismissSummary() {
        _uiState.update { it.copy(lastSetSummary = null) }
    }

    fun dismissPRCelebration() {
        _uiState.update { it.copy(newPersonalRecords = emptyList()) }
    }

    fun setWeight(weight: Float) {
        savedStateHandle[KEY_WEIGHT] = weight
        _uiState.update { it.copy(weight = Kilograms(weight)) }
    }

    fun setTargetReps(reps: Int) {
        savedStateHandle[KEY_TARGET_REPS] = reps
        _uiState.update { it.copy(targetReps = RepCount(reps)) }
    }

    fun setExercise(exercise: ExerciseType) {
        logger.i(TAG, "Exercise changed to: $exercise")
        savedStateHandle[KEY_EXERCISE] = exercise.name
        // Clear rest timer when exercise changes
        skipRest()
        _uiState.update { it.copy(selectedExercise = exercise) }
    }

    // ── Manual Entry ─────────────────────────────────────────────────

    fun toggleManualEntryMode() {
        _uiState.update { it.copy(isManualEntryMode = !it.isManualEntryMode) }
    }

    fun logManualSet(exerciseName: String, weight: Float, reps: Int, notes: String = "") {
        if (exerciseName.isBlank() || weight <= 0f || reps <= 0) return

        // Initialize workout start time on first set
        if (completedSets.isEmpty()) {
            workoutStartTime = System.currentTimeMillis()
        }

        viewModelScope.launch {
            val set = logManualSetUseCase(
                exerciseName = exerciseName,
                weight = weight,
                reps = reps,
                notes = notes
            )
            completedSets.add(set)
            logger.i(TAG, "Manual set logged: $exerciseName ${weight}kg x $reps (${completedSets.size} sets in session)")
            _uiState.update { it.copy(completedSetCount = completedSets.size) }

            // Start rest timer after manual set if enabled
            if (restTimerEnabled) {
                startRestTimer()
            }
        }
    }

    private fun loadCustomExerciseNames() {
        viewModelScope.launch {
            customExerciseRepository.getExerciseNames().collect { names ->
                _uiState.update { it.copy(customExerciseNames = names) }
            }
        }
    }

    // ── Tempo Training ───────────────────────────────────────────────

    private fun loadTempoSettings() {
        viewModelScope.launch {
            settingsRepository.getBooleanSetting(
                SettingsKeys.TEMPO_MODE_ENABLED,
                false
            ).collect { enabled ->
                tempoModeEnabled = enabled
                _uiState.update { it.copy(tempoModeEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getStringSetting(
                SettingsKeys.LAST_TEMPO_PRESCRIPTION,
                ""
            ).collect { notation ->
                if (notation.isNotEmpty()) {
                    TempoPrescription.fromString(notation)?.let { prescription ->
                        _uiState.update { it.copy(tempoPrescription = prescription) }
                    }
                }
            }
        }
    }

    fun toggleTempoMode(enabled: Boolean) {
        tempoModeEnabled = enabled
        _uiState.update { it.copy(tempoModeEnabled = enabled) }
        viewModelScope.launch {
            settingsRepository.saveBooleanSetting(SettingsKeys.TEMPO_MODE_ENABLED, enabled)
        }
    }

    fun setTempoPrescription(prescription: TempoPrescription) {
        _uiState.update { it.copy(tempoPrescription = prescription) }
        viewModelScope.launch {
            settingsRepository.saveStringSetting(
                SettingsKeys.LAST_TEMPO_PRESCRIPTION,
                "${prescription.eccentricSeconds.toInt()}-${prescription.pauseBottomSeconds.toInt()}-" +
                    "${if (prescription.isExplosiveConcentric) "X" else prescription.concentricSeconds.toInt().toString()}-" +
                    "${prescription.pauseTopSeconds.toInt()}"
            )
        }
    }

    // ── Rest Timer ──────────────────────────────────────────────────

    private fun loadRestTimerSettings() {
        viewModelScope.launch {
            settingsRepository.getBooleanSetting(
                SettingsKeys.REST_TIMER_ENABLED,
                true
            ).collect { enabled ->
                restTimerEnabled = enabled
                _uiState.update { it.copy(restTimerEnabled = enabled) }
            }
        }
    }

    fun getRestDurationForExercise(exerciseType: ExerciseType): Int {
        // Default durations in seconds
        return when (exerciseType) {
            ExerciseType.BACK_SQUAT, ExerciseType.FRONT_SQUAT -> 180
            ExerciseType.BENCH_PRESS -> 120
            ExerciseType.DEADLIFT -> 180
            else -> 90
        }
    }

    fun startRestTimer(durationOverride: Int? = null) {
        restTimerJob?.cancel()

        val exercise = _uiState.value.selectedExercise
        val duration = durationOverride ?: getRestDurationForExercise(exercise)

        _uiState.update {
            it.copy(
                restTimerState = com.lifthelper.domain.model.RestTimerState(
                    totalSeconds = duration,
                    remainingSeconds = duration,
                    exerciseType = exercise
                )
            )
        }

        restTimerJob = viewModelScope.launch {
            while (_uiState.value.restTimerState?.isComplete == false) {
                delay(1000)
                _uiState.update { state ->
                    val timer = state.restTimerState ?: return@update state
                    if (timer.isRunning) {
                        val newRemaining = (timer.remainingSeconds - 1).coerceAtLeast(0)
                        if (newRemaining == 0) {
                            feedbackEmitter.emit(FeedbackEvent.RestComplete)
                        }
                        state.copy(restTimerState = timer.copy(remainingSeconds = newRemaining))
                    } else state
                }
            }
        }
    }

    fun skipRest() {
        restTimerJob?.cancel()
        _uiState.update { it.copy(restTimerState = null) }
    }

    fun extendRest(seconds: Int = 30) {
        _uiState.update { state ->
            val timer = state.restTimerState ?: return@update state
            state.copy(
                restTimerState = timer.copy(
                    totalSeconds = timer.totalSeconds + seconds,
                    remainingSeconds = timer.remainingSeconds + seconds
                )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        restTimerJob?.cancel()
        feedbackEmitter.stopCurrentSpeech()
    }
}
