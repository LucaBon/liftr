package com.liftr.ui.screens.workout

import com.liftr.domain.exercise.JumpPathWindow
import com.liftr.domain.model.JumpExcursion
import com.liftr.domain.model.RepJumpData
import com.liftr.domain.calculator.CameraView
import com.liftr.domain.calculator.CameraViewClassifier
import com.liftr.domain.calculator.CenterOfMassCalculator
import android.content.Context
import androidx.camera.core.ImageProxy
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liftr.R
import com.liftr.data.export.VideoShareManager
import com.liftr.service.VideoAnalysisService
import com.liftr.vision.adapter.ImageProxyFrameSource
import com.liftr.vision.camera.CameraManager
import com.liftr.vision.camera.VideoRecordingManager
import dagger.hilt.android.qualifiers.ApplicationContext
import com.liftr.domain.exercise.BarPathAnalyzer
import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.FormIssue
import com.liftr.domain.exercise.RepQualityScorer
import com.liftr.domain.exercise.TempoAdherence
import com.liftr.domain.exercise.TempoCue
import com.liftr.domain.exercise.TempoState
import com.liftr.domain.exercise.TempoTracker
import com.liftr.domain.exercise.VelocityLossState
import com.liftr.domain.exercise.VelocityLossTracker
import com.liftr.domain.feedback.IFeedbackEmitter
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AggregateFormIssue
import com.liftr.domain.model.BarPathData
import com.liftr.domain.model.BarPathPoint
import com.liftr.domain.model.TempoPrescription
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.DepthValidationResult
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.FeedbackEvent
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.Rep
import com.liftr.domain.model.RepQualityScore
import com.liftr.domain.model.Set
import com.liftr.domain.model.SetSummary
import com.liftr.domain.model.VbtZone
import com.liftr.domain.model.Workout
import com.liftr.domain.repository.CalibrationRepository
import com.liftr.domain.repository.SettingsKeys
import com.liftr.domain.repository.SettingsRepository
import com.liftr.domain.usecase.AnalyzeRepUseCase
import com.liftr.domain.usecase.ProcessWorkoutFrameUseCase
import com.liftr.domain.usecase.FrameProcessingResult
import com.liftr.domain.usecase.CheckPersonalRecordsUseCase
import com.liftr.domain.usecase.LogManualSetUseCase
import com.liftr.domain.usecase.SaveWorkoutUseCase
import com.liftr.domain.repository.CustomExerciseRepository
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.RepCount
import com.liftr.domain.vision.IPoseDetector
import com.liftr.ui.common.UiError
import com.liftr.ui.common.toUiError
import com.liftr.domain.model.MovementPhase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.roundToInt

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
    private val videoRecordingManager: VideoRecordingManager,
    private val videoShareManager: VideoShareManager,
    private val cameraManager: CameraManager,
    private val poseDetector: IPoseDetector,
    @ApplicationContext private val context: Context,
    private val logger: Logger,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "WorkoutViewModel"
        private const val KEY_EXERCISE = "exercise_type"
        private const val KEY_WEIGHT = "weight"
        private const val KEY_TARGET_REPS = "target_reps"
        // Camera view check: judge the last few seconds (enough preview snapshots at 2/s)
        private const val VIEW_WINDOW_MS = 3000L
        // Self-heal: how long to wait for an async camera rebind to produce a
        // VideoCapture before giving up, and how often to poll for it.
        private const val REBIND_TIMEOUT_MS = 2500L
        private const val REBIND_POLL_MS = 100L
    }

    // IMPROVE-7: Restore user selections after process death
    private val _uiState = MutableStateFlow(
        (savedStateHandle.get<String>(KEY_EXERCISE)
            ?.let { name -> ExerciseType.entries.find { it.name == name } }
            ?: ExerciseType.FRONT_SQUAT).let { exercise ->
        WorkoutState(
        selectedExercise = exercise,
        weight = weightFor(exercise),
        targetReps = RepCount(savedStateHandle.get<Int>(KEY_TARGET_REPS) ?: 5)
        )
    })
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

    /**
     * Bumped whenever a set starts or stops. Frames captured under one
     * generation must not apply their results under another — frame processing
     * is asynchronous, so a result can land after the set it belongs to is over.
     */
    @Volatile private var setGeneration = 0

    /** Guards against a second save being started while one is in flight. */
    private var isSaving = false

    // Track previous rep count to detect new reps
    private var previousRepCount = 0

    // --- Bar Path: real-time state for overlay ---
    private val _currentBarPathPoints = MutableStateFlow<List<BarPathPoint>>(emptyList())
    val currentBarPathPoints: StateFlow<List<BarPathPoint>> = _currentBarPathPoints.asStateFlow()

    // --- Rep Quality Scoring: per-rep accumulators ---
    private var currentRepBarPathPoints = mutableListOf<BarPathPoint>()
    private var jumpPath = JumpPathWindow()

    /** Recent shoulder width ratios (time, ratio) for the pre-set camera view check. */
    private val viewSamples = ArrayDeque<Pair<Long, Float>>()
    private var currentJumpExcursion: JumpExcursion? = null
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
    private var setJumpHeights = mutableListOf<Meters>()
    private var setApexHeights = mutableListOf<Meters?>()
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

    // --- Post-exercise analysis (Feature 17) ---
    @Volatile private var repCounterEnabled = false
    private var recordingTimerJob: Job? = null

    init {
        logger.d(TAG, "WorkoutViewModel initialized")
        // Surface a friendly message (instead of crashing) when on-device pose
        // detection couldn't load. Camera tracking is disabled; manual entry works.
        if (!poseDetector.isAvailable()) {
            logger.w(TAG, "Pose detection unavailable on this device")
            _uiState.update {
                it.copy(
                    poseDetectionAvailable = false,
                    error = UiError.Message(context.getString(R.string.pose_detection_unavailable))
                )
            }
        }
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
        loadAnalysisModeSettings()
        observeAnalysisService()
    }

    // ── Post-exercise analysis (Feature 17) ──────────────────────────

    private fun loadAnalysisModeSettings() {
        viewModelScope.launch {
            settingsRepository.getStringSetting(
                SettingsKeys.ANALYSIS_MODE,
                SettingsKeys.ANALYSIS_MODE_POST_EXERCISE
            ).collect { mode ->
                val parsed = when (mode) {
                    SettingsKeys.ANALYSIS_MODE_REAL_TIME -> AnalysisMode.REAL_TIME
                    else -> AnalysisMode.POST_EXERCISE
                }
                _uiState.update { it.copy(analysisMode = parsed) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getBooleanSetting(
                SettingsKeys.LIGHTWEIGHT_REP_COUNTER,
                false
            ).collect { enabled ->
                repCounterEnabled = enabled
                _uiState.update { it.copy(repCounterEnabled = enabled) }
            }
        }
    }

    /** Identifies this workout's analyses, so their results come back here and nowhere else. */
    private val analysisOwner = java.util.UUID.randomUUID().toString()

    /** A stopped recording CameraX is still writing out; analyses keep waiting. */
    private val recordingFinalizing = MutableStateFlow(false)

    /**
     * Observes the global analysis service state and folds it into UI state.
     * On completion the analyzed set is persisted exactly like a real-time set.
     */
    private fun observeAnalysisService() {
        viewModelScope.launch {
            VideoAnalysisService.analysisState.collect { state ->
                when (state) {
                    is VideoAnalysisService.AnalysisState.Analyzing -> _uiState.update {
                        it.copy(
                            analysisProgress = AnalysisProgress(
                                currentFrame = state.progress.currentFrame,
                                totalFrames = state.progress.totalFrames,
                                percentComplete = state.progress.percentComplete,
                                currentRep = state.progress.currentRep,
                                queued = state.queued,
                                paused = state.paused
                            ),
                            analysisError = null
                        )
                    }
                    // Queue drained: the next analysis gets its banner back.
                    is VideoAnalysisService.AnalysisState.Idle -> _uiState.update {
                        it.copy(analysisProgress = null, analysisProgressDismissed = false)
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            VideoAnalysisService.results(analysisOwner).collect { result ->
                when (result) {
                    is VideoAnalysisService.AnalysisState.Complete -> onAnalysisComplete(result)
                    is VideoAnalysisService.AnalysisState.Error -> {
                        logger.e(TAG, "Analysis error: ${result.message}")
                        // An analysis that failed to start never ran, so no
                        // Idle follows to drop its banner: drop it here unless
                        // another analysis is still running.
                        val running = VideoAnalysisService.analysisState.value !is
                            VideoAnalysisService.AnalysisState.Idle
                        _uiState.update {
                            if (running) {
                                it.copy(analysisError = result.message)
                            } else {
                                it.copy(
                                    analysisError = result.message,
                                    analysisProgress = null,
                                    analysisProgressDismissed = false
                                )
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        // Analyses wait while a set is recorded, and until its file is
        // written out, so they never compete with it.
        viewModelScope.launch {
            combine(
                _uiState.map { it.isRecording || it.isVideoRecording },
                recordingFinalizing
            ) { recording, finalizing -> recording || finalizing }
                .distinctUntilChanged()
                .collect { VideoAnalysisService.setRecordingActive(it) }
        }
    }

    private fun onAnalysisComplete(state: VideoAnalysisService.AnalysisState.Complete) {
        val playbackFile = java.io.File(state.recordedVideoPath)
            .takeIf { it.exists() && it.length() > 0 }
        val result = PendingSetResult(
            summary = state.summary,
            annotatedVideo = playbackFile?.let { AnnotatedVideoInfo(it.absolutePath, it.length()) },
            playbackIsAnnotated = state.annotatedVideoPath != null &&
                playbackFile?.absolutePath == state.annotatedVideoPath
        )
        _uiState.update { current ->
            // The athlete may already be recording the next set: leave the
            // recording alone (clearing its flag rebinds the camera and kills
            // it). Never pop a summary over it, nor replace one being read:
            // queue it, oldest first, to be opened from the chip.
            val recordingNext = current.isVideoRecording || current.isRecording
            if (recordingNext || current.isSummaryOpen || current.analysisProgressDismissed) {
                current.copy(
                    pendingResults = current.pendingResults + result,
                    // Its "View results" can only open it once nothing is in
                    // the way; otherwise the chip is the notice.
                    analysisCompleteMessage = if (!recordingNext && !current.isSummaryOpen) {
                        context.getString(R.string.analysis_complete_snackbar)
                    } else {
                        null
                    }
                )
            } else {
                current.showing(result)
            }
        }
        saveAnalyzedSet(state.summary)
    }

    /**
     * Persists a set produced by post-exercise analysis. Mirrors the tail of the
     * real-time [stopRecording] (accumulate set, PR check, rest timer).
     */
    private fun saveAnalyzedSet(summary: SetSummary) {
        // A recording started and stopped by mistake: nothing to keep.
        if (summary.completedReps.value == 0) {
            logger.i(TAG, "Analyzed set has no reps, not saved")
            return
        }
        if (completedSets.isEmpty() && workoutStartTime == 0L) {
            workoutStartTime = System.currentTimeMillis()
        }
        val now = System.currentTimeMillis()
        val reps = summary.repScores.mapIndexed { index, score ->
            val v = summary.repVelocities.getOrNull(index) ?: MetersPerSecond(0f)
            Rep(
                setId = 0,
                repNumber = index + 1,
                peakVelocity = v,
                avgVelocity = v,
                timeUnderTension = 0f,
                rangeOfMotion = Meters(0f),
                startPosition = Meters(0f),
                endPosition = Meters(0f),
                eccentricDuration = 0f,
                concentricDuration = 0f,
                velocityProfile = emptyList(),
                positionProfile = emptyList(),
                timestamp = now,
                qualityScore = score.totalScore
            )
        }
        val completedSet = Set(
            workoutId = 0,
            exerciseType = summary.exerciseType,
            weight = summary.weight,
            targetReps = summary.targetReps,
            completedReps = summary.completedReps,
            avgVelocity = summary.avgVelocity,
            peakVelocity = summary.peakVelocity,
            jumpHeight = summary.bestJumpHeight,
            timestamp = now,
            reps = withJumpData(reps, summary)
        )
        completedSets.add(completedSet)
        _uiState.update { it.copy(completedSetCount = completedSets.size) }
        logger.i(TAG, "Analyzed set persisted (${completedSets.size} sets in session)")

        checkForPersonalRecords(completedSet)

        // Analysis runs in the background precisely so the lifter can start the
        // next set while it finishes. Starting the rest timer on completion
        // would then drop a full-screen scrim over a set already in progress.
        val state = _uiState.value
        if (restTimerEnabled && !state.isRecording && !state.isVideoRecording) {
            startRestTimer()
        }
    }

    /**
     * Attach each jump's measurements and center-of-mass path to its rep, so
     * they are saved with the set; reps of other exercises are unchanged.
     */
    private fun withJumpData(reps: List<Rep>, summary: SetSummary): List<Rep> {
        if (!summary.exerciseType.isJumpExercise()) return reps
        return reps.mapIndexed { index, rep ->
            val height = summary.repJumpHeights.getOrNull(index) ?: return@mapIndexed rep
            rep.copy(
                jump = RepJumpData.from(
                    flightHeightCm = height.value * 100f,
                    apexHeightCm = summary.repApexHeights.getOrNull(index)?.let { it.value * 100f },
                    barPath = summary.repBarPaths.getOrNull(index)
                )
            )
        }
    }

    /** Dismiss the post-analysis error banner. */
    fun clearAnalysisError() {
        _uiState.update { it.copy(analysisError = null) }
    }

    /** Hide the analysis progress banner; analysis keeps running in background. */
    fun dismissAnalysisProgress() {
        _uiState.update { it.copy(analysisProgressDismissed = true) }
    }

    /** Open the oldest summary not seen yet; the chip keeps counting the rest. */
    fun showPendingAnalysisResults() {
        _uiState.update {
            val next = it.pendingResults.firstOrNull()
            if (it.isSummaryOpen || next == null) {
                it.copy(analysisCompleteMessage = null)
            } else {
                it.copy(analysisCompleteMessage = null, pendingResults = it.pendingResults.drop(1)).showing(next)
            }
        }
    }

    fun clearAnalysisCompleteMessage() {
        _uiState.update { it.copy(analysisCompleteMessage = null) }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        _uiState.update { it.copy(recordingElapsedSeconds = 0) }
        recordingTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _uiState.update { it.copy(recordingElapsedSeconds = it.recordingElapsedSeconds + 1) }
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
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
                com.liftr.domain.feedback.FeedbackLanguage.DEFAULT.name
            ).collect { name ->
                feedbackEmitter.setLanguage(
                    com.liftr.domain.feedback.FeedbackLanguage.fromString(name)
                )
            }
        }
        viewModelScope.launch {
            settingsRepository.getStringSetting(
                SettingsKeys.FEEDBACK_VERBOSITY,
                com.liftr.domain.feedback.FeedbackVerbosity.DEFAULT.name
            ).collect { name ->
                feedbackEmitter.setVerbosity(
                    com.liftr.domain.feedback.FeedbackVerbosity.fromString(name)
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
                    // Tie this frame to the set it was captured in, so a result
                    // that lands after the set boundary can be discarded.
                    val generation = setGeneration
                    val result = processFrameUseCase(
                        ProcessWorkoutFrameUseCase.Params(
                            frame = ImageProxyFrameSource(frame),
                            timestamp = System.currentTimeMillis(),
                            calibrationData = calibrationData,
                            exerciseType = _uiState.value.selectedExercise,
                            isRecording = _uiState.value.isRecording,
                            exerciseState = exerciseState,
                            currentRepCount = _uiState.value.repCount.value,
                            weight = _uiState.value.weight
                        )
                    )
                    if (generation != setGeneration) {
                        // The set was stopped (and possibly a new one started)
                        // while this frame was in flight. Applying it now would
                        // overwrite the fresh state with the previous set's
                        // mid-rep phase and rep count — which can immediately
                        // re-trigger auto-stop and bank a garbage set.
                        logger.d(TAG, "Dropping frame result from previous set")
                        return@launch
                    }
                    handleFrameProcessingResult(result)
                } finally {
                    isProcessingFrame = false
                }
            }
        }
    }

    private suspend fun handleFrameProcessingResult(result: FrameProcessingResult) {
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

                onViewSample(result.poseResult)

                _uiState.update { state ->
                    var updatedState = state.copy(
                        poseResult = result.poseResult,
                        velocityData = result.velocityData,
                        repCount = RepCount(newRepCount),
                        calibrationQuality = result.calibrationQuality,
                        lastJumpHeight = result.phaseResult?.jump?.height ?: state.lastJumpHeight,
                        error = null
                    )

                    // VBT: Update zone and velocity loss on rep completion
                    val repAvgVelocity = repVelocityOf(result)
                    if (repJustCounted && repAvgVelocity != null) {

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
    private suspend fun collectRepQualityData(
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

        // A jump's path runs from the dip through the flight, which the phases
        // above leave out: keep every point and cut the window at landing.
        if (_uiState.value.selectedExercise.isJumpExercise()) {
            val point = extractBarPathPoint(result.poseResult, result.velocityData, _uiState.value.selectedExercise)
            val scale = calibrationData?.let { JumpPathWindow.cmPerUnitY(result.poseResult, it.userHeight) }
            jumpPath.onFrame(point, newPhase, now, scale)
            val jump = result.phaseResult?.jump
            if (repJustCounted && jump != null) {
                // Path points carry the wall clock, the jump's times the pose clock.
                val toWallClock = now - result.poseResult.timestamp
                val window = jumpPath.pathForLanding(
                    now, jump.flightTimeMs,
                    jump.takeoffAtMs?.let { it.toLong() + toWallClock },
                    jump.landingAtMs?.let { it.toLong() + toWallClock }
                )
                currentRepBarPathPoints.clear()
                currentRepBarPathPoints.addAll(window.points)
                currentJumpExcursion = window.excursion
                window.excursion?.let {
                    logger.i(TAG, "Jump: dip %.1f cm, peak %.1f cm above standing".format(it.countermovementCm, it.peakRiseCm))
                    logger.i(TAG, "Jump fit check: ${it.fitCheck?.describe() ?: "n/a"}")
                }
                _currentBarPathPoints.value = currentRepBarPathPoints.toList()
            }
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
    private suspend fun scoreCurrentRep(result: FrameProcessingResult.Success, now: Long) {
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
                        finalVelocityData = result.velocityData,
                        phaseState = exerciseState
                    )
                )
            }.onFailure {
                // Never swallow cancellation: the caller's scope is dying.
                if (it is kotlinx.coroutines.CancellationException) throw it
                logger.w(TAG, "AnalyzeRepUseCase failed: ${it.message}")
            }.getOrNull()
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
            points = currentRepBarPathPoints.toList(),
            excursion = currentJumpExcursion
        )
        currentJumpExcursion = null
        setBarPaths.add(barPathData)

        setRepScores.add(score)
        previousConcentricDurations.add(concentricDuration)

        // Track per-rep velocity
        val jump = result.phaseResult?.jump
        val repAvgVelocity = repVelocityOf(result) ?: MetersPerSecond(0f)
        val repPeakVelocity = jump?.takeoffVelocity
            ?: result.velocityData?.instantaneous
            ?: MetersPerSecond(0f)
        repVelocityOf(result)?.let { setRepVelocities.add(it) }
        jump?.let {
            setJumpHeights.add(it.height)
            setApexHeights.add(it.apexHeight)
        }

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
     * The velocity a counted rep is judged by: a jump's takeoff velocity (from
     * flight time), otherwise the tracked bar velocity. Using takeoff velocity
     * lets VBT zones and velocity loss work unchanged for jumps.
     */
    private fun repVelocityOf(result: FrameProcessingResult.Success): MetersPerSecond? =
        result.phaseResult?.jump?.takeoffVelocity ?: result.velocityData?.average

    /**
     * Extracts a full bar path point from the pose result and velocity data.
     * The tracked body part depends on the exercise type.
     */
    private fun extractBarPathPoint(
        poseResult: PoseResult,
        velocityData: com.liftr.domain.model.VelocityData?,
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
            ExerciseType.VERTICAL_JUMP ->
                CenterOfMassCalculator.centerOfMass(poseResult) ?: return null
            ExerciseType.LOADED_JUMP -> CenterOfMassCalculator.centerOfMass(
                poseResult,
                CenterOfMassCalculator.loadFraction(
                    _uiState.value.weight.value,
                    calibrationData?.anthropometricData?.userWeight
                )
            ) ?: return null
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

    /**
     * Start a tracked set. Dispatches to the real-time or post-exercise pipeline
     * based on the configured [AnalysisMode].
     */
    fun startRecording() {
        if (!_uiState.value.poseDetectionAvailable) {
            logger.w(TAG, "startRecording ignored: pose detection unavailable")
            _uiState.update {
                it.copy(error = UiError.Message(context.getString(R.string.pose_detection_unavailable)))
            }
            return
        }
        when (_uiState.value.analysisMode) {
            AnalysisMode.POST_EXERCISE -> startPostExerciseRecording()
            AnalysisMode.REAL_TIME -> startRealTimeRecording()
        }
    }

    /**
     * Begin recording video for post-exercise analysis. The camera was already
     * bound in RECORDING mode by the screen, so we just start the [Recording].
     */
    private fun startPostExerciseRecording() {
        setGeneration++
        val videoCapture = cameraManager.getVideoCapture()
        if (videoCapture != null) {
            beginPostExerciseRecording(videoCapture)
            return
        }

        // A navigation/timing race occasionally leaves the shared camera bound in
        // analysis-only mode (no VideoCapture). Rather than failing outright,
        // self-heal by rebinding into recording mode and retrying once ready.
        logger.w(TAG, "No VideoCapture bound; attempting camera rebind for recording")
        val rebindInitiated = cameraManager.ensureRecordingBinding(
            videoRecordingManager = videoRecordingManager,
            withRepCounter = repCounterEnabled
        )
        if (!rebindInitiated) {
            logger.e(TAG, "No VideoCapture bound and rebind could not be attempted")
            _uiState.update {
                it.copy(analysisError = "Camera not ready for recording. Try again.")
            }
            return
        }

        viewModelScope.launch {
            val healed = awaitVideoCapture(REBIND_TIMEOUT_MS)
            if (healed != null) {
                logger.i(TAG, "Camera rebind succeeded; starting recording")
                beginPostExerciseRecording(healed)
            } else {
                logger.e(TAG, "Camera rebind timed out; still no VideoCapture")
                _uiState.update {
                    it.copy(analysisError = "Camera not ready for recording. Try again.")
                }
            }
        }
    }

    /**
     * Poll for a bound [VideoCapture] for up to [timeoutMs], since rebinding the
     * camera into recording mode is asynchronous (CameraX provider future).
     */
    private suspend fun awaitVideoCapture(timeoutMs: Long): VideoCapture<Recorder>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            cameraManager.getVideoCapture()?.let { return it }
            delay(REBIND_POLL_MS)
        }
        return cameraManager.getVideoCapture()
    }

    /** Start the actual recording once a [VideoCapture] is confirmed bound. */
    private fun beginPostExerciseRecording(videoCapture: VideoCapture<Recorder>) {
        if (completedSets.isEmpty() && workoutStartTime == 0L) {
            workoutStartTime = System.currentTimeMillis()
        }

        logger.i(TAG, "START VIDEO RECORDING - Exercise: ${_uiState.value.selectedExercise}")
        exerciseState = ExercisePhaseState.initial()
        previousRepCount = 0
        previousPhase = MovementPhase.IDLE

        val started = videoRecordingManager.startRecording(
            videoCapture = videoCapture,
            exerciseType = _uiState.value.selectedExercise
        ) { event ->
            // A recording that dies on its own (storage full, camera taken)
            // never gets a Stop; without this the set looks like it is still
            // being recorded and every queued analysis stays paused.
            if (event is VideoRecordEvent.Finalize && event.hasError() &&
                _uiState.value.isVideoRecording
            ) {
                onRecordingFailed(event.error)
            }
        }
        if (!started) {
            logger.e(TAG, "VideoRecordingManager refused to start recording")
            _uiState.update {
                it.copy(analysisError = "Recording failed — nothing to analyze.")
            }
            return
        }

        // When the lite rep counter is enabled, isRecording drives the live
        // counter via processFrame; otherwise the frame pipeline stays idle so
        // the GPU is dedicated to video capture.
        _uiState.update {
            it.copy(
                isVideoRecording = true,
                isRecording = repCounterEnabled,
                repCount = RepCount(0),
                error = null,
                analysisError = null,
                lastSetSummary = null,
                isSetSummaryVisible = false,
                currentRepScore = null
            )
        }
        startRecordingTimer()
    }

    private fun onRecordingFailed(error: Int) {
        logger.e(TAG, "Recording failed on its own (error=$error)")
        setGeneration++
        stopRecordingTimer()
        _currentBarPathPoints.value = emptyList()
        videoRecordingManager.abandonRecording()
        _uiState.update {
            it.copy(
                isVideoRecording = false,
                isRecording = false,
                analysisError = "Recording failed — nothing to analyze."
            )
        }
    }

    private fun startRealTimeRecording() {
        setGeneration++
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
        jumpPath = JumpPathWindow()
        currentJumpExcursion = null
        _currentBarPathPoints.value = emptyList()
        currentRepFormIssues.clear()
        currentRepPoses.clear()
        currentRepDepthValidation = null
        currentRepEccentricStartTime = 0L
        currentRepBottomTime = 0L
        setRepScores.clear()
        setRepVelocities.clear()
        setJumpHeights.clear()
        setApexHeights.clear()
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
                lastJumpHeight = null,
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
            val jump = result.phaseResult?.jump
            feedbackEmitter.emit(
                if (jump != null) FeedbackEvent.JumpMeasured((jump.height.value * 100).roundToInt())
                else FeedbackEvent.RepCounted(newRepCount)
            )

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

    /**
     * Stop a tracked set. Dispatches based on [AnalysisMode].
     */
    fun stopRecording() {
        when (_uiState.value.analysisMode) {
            AnalysisMode.POST_EXERCISE -> stopPostExerciseRecording()
            AnalysisMode.REAL_TIME -> stopRealTimeRecording()
        }
    }

    /**
     * Stop video recording and hand the file off to [VideoAnalysisService] for
     * heavy-model analysis. The summary arrives later via [observeAnalysisService].
     */
    private fun stopPostExerciseRecording() {
        // Idempotence guard: a second stop (double tap, or a user tap racing
        // auto-stop) would find the recording already handed off, report a
        // spurious "recording failed", and wipe the progress of the analysis
        // the first call just started.
        if (!_uiState.value.isVideoRecording) {
            logger.d(TAG, "stopPostExerciseRecording ignored - not recording")
            return
        }
        setGeneration++
        stopRecordingTimer()
        // Clear any live-counter overlay state.
        _currentBarPathPoints.value = emptyList()

        val calibration = calibrationData
        if (calibration == null) {
            logger.e(TAG, "Cannot analyze: calibration missing")
            viewModelScope.launch {
                videoRecordingManager.stopRecording()
                _uiState.update {
                    it.copy(
                        isVideoRecording = false,
                        isRecording = false,
                        analysisError = "Calibration required before analysis."
                    )
                }
            }
            return
        }

        _uiState.update {
            it.copy(
                isRecording = false,
                isVideoRecording = false,
                // An analysis already running keeps its progress; the service
                // adds this recording to its queue count.
                analysisProgress = it.analysisProgress ?: AnalysisProgress(0, 1, 0f, 0),
                analysisProgressDismissed = false
            )
        }

        recordingFinalizing.value = true
        viewModelScope.launch {
            val videoFile = try {
                videoRecordingManager.stopRecording()
            } finally {
                recordingFinalizing.value = false
            }
            if (videoFile == null) {
                logger.e(TAG, "Cannot analyze: recording file missing after finalize")
                _uiState.update {
                    it.copy(
                        analysisProgress = null,
                        analysisError = "Recording failed — nothing to analyze."
                    )
                }
                return@launch
            }

            val state = _uiState.value
            val intent = VideoAnalysisService.createIntent(
                context = context,
                owner = analysisOwner,
                videoFile = videoFile,
                exerciseType = state.selectedExercise,
                weight = state.weight,
                targetReps = state.targetReps.value,
                calibrationData = calibration,
                tempoPrescription = if (state.tempoModeEnabled) state.tempoPrescription else null
            )
            ContextCompat.startForegroundService(context, intent)
            logger.i(TAG, "Post-exercise analysis service started for ${videoFile.name} (${videoFile.length()} bytes)")
        }
    }

    private fun stopRealTimeRecording() {
        // Idempotence guard: generateSetSummary() stays non-null until the next
        // set starts, so a second stop would build the same Set again and append
        // a duplicate to completedSets (and re-run the PR check). Reachable by
        // double-tapping Stop, or by a queued user tap arriving just after
        // auto-stop already fired.
        if (!_uiState.value.isRecording) {
            logger.d(TAG, "stopRealTimeRecording ignored - not recording")
            return
        }
        setGeneration++
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
                jumpHeight = summary.bestJumpHeight,
                timestamp = System.currentTimeMillis(),
                reps = withJumpData(setReps.toList(), summary)
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
                isSetSummaryVisible = summary != null,
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
        // Re-entrancy guard: without it, two taps before the first write
        // finishes both pass the isEmpty() check and persist the workout twice.
        if (isSaving) {
            logger.w(TAG, "Save already in progress")
            return
        }
        isSaving = true

        // Take the sets out of the accumulator up front. Clearing after the
        // suspended write instead would discard anything appended in the
        // meantime — a background analysis finishing mid-save, or a manual set —
        // without ever having persisted it.
        val setsToSave = completedSets.toList()
        val startedAt = workoutStartTime
        completedSets.clear()
        _uiState.update { it.copy(completedSetCount = 0) }

        viewModelScope.launch {
            val workout = Workout(
                date = Date(startedAt),
                duration = System.currentTimeMillis() - startedAt,
                sets = setsToSave
            )

            val result = saveWorkoutUseCase(SaveWorkoutUseCase.Params(workout))
            result.onSuccess { id ->
                logger.i(TAG, "Workout saved: id=$id, sets=${setsToSave.size}")
                workoutStartTime = 0L
                _uiState.update { it.copy(workoutSaved = true) }
            }.onFailure { error ->
                logger.e(TAG, "Failed to save workout: ${error.message}", error)
                // Put the sets back so the session isn't lost, keeping any that
                // arrived while the write was in flight.
                completedSets.addAll(0, setsToSave)
                _uiState.update {
                    it.copy(
                        completedSetCount = completedSets.size,
                        analysisError = "Could not save workout. Your sets are still here."
                    )
                }
            }
            isSaving = false
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
            repBarPaths = setBarPaths.toList(),
            repJumpHeights = setJumpHeights.toList(),
            repApexHeights = setApexHeights.toList()
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
     * Dismisses the post-set summary sheet. Any annotated video that was neither
     * shared nor saved is a transient app-private file; sharing/saving copies it
     * out, so we delete the original on dismiss to avoid orphan accumulation.
     */
    fun dismissSummary() {
        _uiState.value.annotatedVideo?.let { info ->
            runCatching { java.io.File(info.path).delete() }
        }
        _uiState.update {
            it.copy(
                lastSetSummary = null,
                isSetSummaryVisible = false,
                annotatedVideo = null,
                playbackIsAnnotated = false
            )
        }
    }

    // ── Annotated video (Feature 09) ─────────────────────────────────

    /** Open the system share sheet for the annotated workout video. */
    fun shareAnnotatedVideo() {
        val info = _uiState.value.annotatedVideo ?: return
        val file = java.io.File(info.path)
        if (!file.exists()) {
            logger.w(TAG, "shareAnnotatedVideo: file missing ${info.path}")
            return
        }
        videoShareManager.share(
            file = file,
            chooserTitle = context.getString(R.string.video_share_chooser_title)
        )
    }

    /** Copy the annotated workout video into the device gallery. */
    fun saveAnnotatedVideoToGallery() {
        val info = _uiState.value.annotatedVideo ?: return
        viewModelScope.launch {
            val saved = runCatching {
                videoShareManager.saveToGallery(java.io.File(info.path))
            }.getOrDefault(false)
            val message = if (saved) {
                context.getString(R.string.video_saved_to_gallery)
            } else {
                context.getString(R.string.video_save_failed)
            }
            _uiState.update { it.copy(videoMessage = message) }
        }
    }

    /** Delete the annotated workout video and dismiss its preview. */
    fun deleteAnnotatedVideo() {
        _uiState.value.annotatedVideo?.let { info ->
            runCatching { java.io.File(info.path).delete() }
        }
        _uiState.update { it.copy(annotatedVideo = null) }
    }

    /** Clears the transient video snackbar message. */
    fun clearVideoMessage() {
        _uiState.update { it.copy(videoMessage = null) }
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
        viewSamples.clear()
        _uiState.update {
            it.copy(selectedExercise = exercise, weight = weightFor(exercise), cameraViewWarning = false)
        }
    }

    /**
     * Checks, before a jump set, that the camera sees the athlete side-on: fed
     * by the live pose stream or by preview snapshots when there is none.
     */
    fun onViewSample(pose: PoseResult) {
        val state = _uiState.value
        if (!state.selectedExercise.isJumpExercise() || state.isRecording || state.isVideoRecording) {
            viewSamples.clear()
            return
        }
        val now = System.currentTimeMillis()
        CameraViewClassifier.shoulderWidthRatio(pose)?.let { viewSamples.addLast(now to it) }
        while (viewSamples.isNotEmpty() && now - viewSamples.first().first > VIEW_WINDOW_MS) {
            viewSamples.removeFirst()
        }
        val frontView = CameraViewClassifier.classify(viewSamples.map { it.second }) == CameraView.FRONT
        if (frontView != state.cameraViewWarning) {
            logger.i(TAG, if (frontView) "Jump camera view: front" else "Jump camera view: no longer front")
            _uiState.update { it.copy(cameraViewWarning = frontView) }
        }
    }

    /**
     * The entered weight, or none for a bodyweight exercise. The entered value
     * is kept, so it comes back when switching to a loaded exercise.
     */
    private fun weightFor(exercise: ExerciseType): Kilograms =
        Kilograms(if (exercise.hasExternalLoad()) savedStateHandle.get<Float>(KEY_WEIGHT) ?: 0f else 0f)

    // ── Manual Entry ─────────────────────────────────────────────────

    fun toggleManualEntryMode() {
        _uiState.update { it.copy(isManualEntryMode = !it.isManualEntryMode) }
    }

    fun logManualSet(exerciseName: String, weight: Float, reps: Int, notes: String = "") {
        if (exerciseName.isBlank() || weight < 0f || reps <= 0) return

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
            ExerciseType.VERTICAL_JUMP, ExerciseType.LOADED_JUMP -> 60
            else -> 90
        }
    }

    fun startRestTimer(durationOverride: Int? = null) {
        restTimerJob?.cancel()

        val exercise = _uiState.value.selectedExercise
        val duration = durationOverride ?: getRestDurationForExercise(exercise)

        _uiState.update {
            it.copy(
                restTimerState = com.liftr.domain.model.RestTimerState(
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
        recordingTimerJob?.cancel()
        // Must not block here: CameraX delivers Finalize on the main executor,
        // which is the thread onCleared runs on — awaiting it would deadlock
        // until the finalize timeout expires.
        videoRecordingManager.abandonRecording()
        // Release the camera buffer that was latched for processing but never
        // consumed, otherwise the frame leaks when the screen goes away.
        latestFrame.getAndSet(null)?.close()
        // Drop any annotated video that was never shared or saved.
        _uiState.value.annotatedVideo?.let { info ->
            runCatching { java.io.File(info.path).delete() }
        }
        _uiState.value.pendingResults.forEach { result ->
            result.annotatedVideo?.let { runCatching { java.io.File(it.path).delete() } }
        }
        // No set is being recorded once this screen is gone, and nobody is
        // left to open the summaries of analyses still queued.
        VideoAnalysisService.setRecordingActive(false)
        VideoAnalysisService.release(analysisOwner)
        feedbackEmitter.stopCurrentSpeech()
    }
}
