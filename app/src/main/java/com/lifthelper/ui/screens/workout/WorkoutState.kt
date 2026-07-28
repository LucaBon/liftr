package com.lifthelper.ui.screens.workout

import com.lifthelper.domain.exercise.VelocityLossState
import com.lifthelper.domain.model.CalibrationQuality
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.RepQualityScore
import com.lifthelper.domain.model.PersonalRecord
import com.lifthelper.domain.model.RestTimerState
import com.lifthelper.domain.model.SetSummary
import com.lifthelper.domain.model.TempoPrescription
import com.lifthelper.domain.exercise.TempoCue
import com.lifthelper.domain.exercise.TempoState
import com.lifthelper.domain.model.VbtZone
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.RepCount
import com.lifthelper.ui.common.UiError

data class WorkoutState(
    val isActive: Boolean = false,
    val selectedExercise: ExerciseType = ExerciseType.FRONT_SQUAT,
    val weight: Kilograms = Kilograms(0f),
    val targetReps: RepCount = RepCount(5),
    val poseResult: PoseResult? = null,
    val velocityData: VelocityData? = null,
    val repCount: RepCount = RepCount(0),
    val isRecording: Boolean = false,
    val error: UiError? = null,
    val isLoading: Boolean = false,
    // False when on-device pose detection couldn't initialize (e.g. unsupported
    // ABI). Camera tracking is disabled and the UI explains; manual entry still works.
    val poseDetectionAvailable: Boolean = true,
    val targetVelocityRange: ClosedFloatingPointRange<Float>? = null,
    val calibrationQuality: CalibrationQuality = CalibrationQuality.NONE,
    val vbtZone: VbtZone? = null,
    val velocityLossState: VelocityLossState = VelocityLossState(),
    val velocityLossAlertDismissed: Boolean = false,
    val estimatedOneRM: Kilograms? = null,
    val vbtZonesEnabled: Boolean = true,
    val currentRepScore: RepQualityScore? = null,
    // Real-time cue: `false` when the most recent completed rep failed depth
    // validation (squats only). Drives the red flash on the rep counter badge
    // and is cleared to `null` when the next rep starts or after a short delay.
    val lastRepDepthOk: Boolean? = null,
    val lastSetSummary: SetSummary? = null,
    val isSetSummaryVisible: Boolean = false,
    val completedSetCount: Int = 0,
    val newPersonalRecords: List<PersonalRecord> = emptyList(),
    val workoutSaved: Boolean = false,
    // Manual entry
    val isManualEntryMode: Boolean = false,
    val customExerciseNames: List<String> = emptyList(),
    // Rest timer
    val restTimerState: RestTimerState? = null,
    val restTimerEnabled: Boolean = true,
    // Tempo training
    val tempoModeEnabled: Boolean = false,
    val tempoPrescription: TempoPrescription? = null,
    val tempoState: TempoState? = null,
    val tempoCue: TempoCue? = null,
    // Post-exercise analysis (Feature 17)
    val analysisMode: AnalysisMode = AnalysisMode.POST_EXERCISE,
    val repCounterEnabled: Boolean = false,
    val isVideoRecording: Boolean = false,
    val recordingElapsedSeconds: Int = 0,
    val analysisProgress: AnalysisProgress? = null,
    // When true the compact banner is hidden but analysis keeps running.
    val analysisProgressDismissed: Boolean = false,
    val analysisError: String? = null,
    // Playable set video (raw recording or annotated export when enabled).
    val annotatedVideo: AnnotatedVideoInfo? = null,
    val playbackIsAnnotated: Boolean = false,
    val videoMessage: String? = null,
    // One-shot snackbar when analysis finishes while the banner was dismissed.
    val analysisCompleteMessage: String? = null,
    /**
     * Analyses that finished while the athlete had dismissed the progress
     * banner and whose summary has not been opened yet.
     *
     * The snackbar alone is not enough: it times out, and a queued analysis can
     * land a minute or more after the set it belongs to, so its result was easy
     * to miss entirely. This drives a persistent affordance that survives until
     * the summary is actually viewed. The sets themselves are always persisted
     * regardless of whether this is ever acted on.
     */
    val unseenAnalysisCount: Int = 0
)

/**
 * Metadata for a freshly rendered annotated workout video (Feature 09).
 *
 * @property path Absolute path to the app-private MP4
 * @property sizeBytes File size used to show an estimate in the preview card
 */
data class AnnotatedVideoInfo(
    val path: String,
    val sizeBytes: Long
)

/**
 * Which analysis pipeline a tracked set uses.
 */
enum class AnalysisMode {
    /** Legacy: lite model runs on every frame during the set. */
    REAL_TIME,

    /** Default: record video during the set, analyze afterwards with the heavy model. */
    POST_EXERCISE
}

/**
 * Progress of the post-exercise analysis, surfaced as a dismissible banner.
 */
data class AnalysisProgress(
    val currentFrame: Int,
    val totalFrames: Int,
    val percentComplete: Float,
    val currentRep: Int
)
