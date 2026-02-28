package com.lifthelper.ui.screens.workout

import androidx.camera.core.ImageProxy
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.domain.exercise.ExercisePhaseState
import com.lifthelper.domain.exercise.FormIssue
import com.lifthelper.domain.exercise.RepQualityScorer
import com.lifthelper.domain.exercise.Severity
import com.lifthelper.domain.exercise.VelocityLossState
import com.lifthelper.domain.exercise.VelocityLossTracker
import com.lifthelper.domain.feedback.IFeedbackEmitter
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.AggregateFormIssue
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
import com.lifthelper.domain.usecase.ProcessWorkoutFrameUseCase
import com.lifthelper.domain.usecase.FrameProcessingResult
import com.lifthelper.domain.usecase.CheckPersonalRecordsUseCase
import com.lifthelper.domain.usecase.SaveWorkoutUseCase
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.value.RepCount
import com.lifthelper.ui.common.UiError
import com.lifthelper.ui.common.toUiError
import com.lifthelper.vision.tracking.MovementPhase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@HiltViewModel
class WorkoutViewModel @Inject constructor(
    private val processFrameUseCase: ProcessWorkoutFrameUseCase,
    private val saveWorkoutUseCase: SaveWorkoutUseCase,
    private val checkPersonalRecordsUseCase: CheckPersonalRecordsUseCase,
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

    // --- Rep Quality Scoring: per-rep accumulators ---
    private var currentRepBarXPositions = mutableListOf<Float>()
    private var currentRepFormIssues = mutableListOf<FormIssue>()
    private var currentRepEccentricStartTime: Long = 0L
    private var currentRepBottomTime: Long = 0L
    private var currentRepDepthValidation: DepthValidationResult? = null
    private var previousPhase: MovementPhase = MovementPhase.IDLE

    // --- Rep Quality Scoring: per-set accumulators ---
    private var setRepScores = mutableListOf<RepQualityScore>()
    private var setRepVelocities = mutableListOf<MetersPerSecond>()
    private var previousConcentricDurations = mutableListOf<Float>()

    // --- Per-set Rep domain objects for persistence ---
    private var setReps = mutableListOf<Rep>()

    // --- Session-level accumulators for workout persistence ---
    private val completedSets = mutableListOf<Set>()
    private var workoutStartTime: Long = 0L

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
                    currentRepBarXPositions.clear()
                    currentRepFormIssues.clear()
                    currentRepDepthValidation = null
                    currentRepEccentricStartTime = now
                    currentRepBottomTime = 0L
                }
                MovementPhase.BOTTOM -> {
                    currentRepBottomTime = now
                    // Store depth validation from phase result
                    result.phaseResult?.depthValidation?.let {
                        currentRepDepthValidation = it
                    }
                }
                else -> { /* No special action for other transitions */ }
            }
        }

        // Collect bar X position during active rep phases
        if (newPhase in listOf(MovementPhase.ECCENTRIC, MovementPhase.BOTTOM, MovementPhase.CONCENTRIC)) {
            extractBarXPosition(result.poseResult, _uiState.value.selectedExercise)?.let {
                currentRepBarXPositions.add(it)
            }
            // Collect unique form issues during the rep
            result.formValidation.issues.forEach { issue ->
                if (currentRepFormIssues.none { it.message == issue.message }) {
                    currentRepFormIssues.add(issue)
                }
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
     */
    private fun scoreCurrentRep(result: FrameProcessingResult.Success, now: Long) {
        val eccentricDuration = if (currentRepBottomTime > 0 && currentRepEccentricStartTime > 0) {
            (currentRepBottomTime - currentRepEccentricStartTime) / 1000f
        } else 0f

        val concentricDuration = if (currentRepBottomTime > 0) {
            (now - currentRepBottomTime) / 1000f
        } else 0f

        val score = RepQualityScorer.scoreRep(
            exerciseType = _uiState.value.selectedExercise,
            depthValidation = currentRepDepthValidation,
            barXPositions = currentRepBarXPositions.toList(),
            eccentricDuration = eccentricDuration,
            concentricDuration = concentricDuration,
            previousConcentricDurations = previousConcentricDurations.toList(),
            formIssues = currentRepFormIssues.toList()
        )

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

        // Update UI with latest rep score
        _uiState.update { it.copy(currentRepScore = score) }
    }

    /**
     * Extracts the horizontal bar tracking position from the pose result.
     * The tracked body part depends on the exercise type.
     */
    private fun extractBarXPosition(poseResult: PoseResult, exerciseType: ExerciseType): Float? {
        val landmarks = poseResult.landmarks
        return when (exerciseType) {
            ExerciseType.BACK_SQUAT, ExerciseType.FRONT_SQUAT -> {
                val left = landmarks.getOrNull(PoseResult.LEFT_SHOULDER)
                val right = landmarks.getOrNull(PoseResult.RIGHT_SHOULDER)
                averageX(left, right)
            }
            ExerciseType.BENCH_PRESS, ExerciseType.DEADLIFT -> {
                val left = landmarks.getOrNull(PoseResult.LEFT_WRIST)
                val right = landmarks.getOrNull(PoseResult.RIGHT_WRIST)
                averageX(left, right)
            }
            else -> null
        }
    }

    private fun averageX(a: PoseKeypoint?, b: PoseKeypoint?): Float? {
        if (a == null || b == null) return null
        if (a.visibility < 0.5f || b.visibility < 0.5f) return null
        return (a.x + b.x) / 2f
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
        currentRepBarXPositions.clear()
        currentRepFormIssues.clear()
        currentRepDepthValidation = null
        currentRepEccentricStartTime = 0L
        currentRepBottomTime = 0L
        setRepScores.clear()
        setRepVelocities.clear()
        previousConcentricDurations.clear()
        setReps.clear()
        previousPhase = MovementPhase.IDLE

        _uiState.update {
            it.copy(
                isRecording = true,
                repCount = RepCount(0),
                error = null,
                vbtZone = null,
                velocityLossState = VelocityLossTracker.reset(velocityLossThreshold),
                currentRepScore = null,
                lastSetSummary = null
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

            // Check if target reps reached
            if (newRepCount >= _uiState.value.targetReps.value && _uiState.value.targetReps.value > 0) {
                feedbackEmitter.emit(FeedbackEvent.SetComplete)
            }
        }

        // Velocity loss alert
        val lossState = _uiState.value.velocityLossState
        if (repJustCounted && lossState.shouldStop) {
            feedbackEmitter.emit(FeedbackEvent.VelocityLossAlert(lossState.currentLossPercent))
        }

        // Form issue alerts
        result.formValidation.issues.forEach { issue ->
            when (issue.severity) {
                Severity.WARNING -> feedbackEmitter.emit(FeedbackEvent.FormWarning(issue))
                Severity.CRITICAL -> feedbackEmitter.emit(FeedbackEvent.FormCritical(issue))
                Severity.INFO -> { /* no audio/haptic for informational messages */ }
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

        _uiState.update {
            it.copy(
                isRecording = false,
                lastSetSummary = summary,
                completedSetCount = completedSets.size
            )
        }

        if (summary != null) {
            logger.i(TAG, "Set summary: overall=${summary.overallScore}/100 " +
                "(${summary.overallGrade.label}), reps=${summary.completedReps.value}")
        }
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
            aggregateFormIssues = aggregateFormIssues(scores)
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
        _uiState.update { it.copy(selectedExercise = exercise) }
    }

    override fun onCleared() {
        super.onCleared()
        feedbackEmitter.stopCurrentSpeech()
    }
}
