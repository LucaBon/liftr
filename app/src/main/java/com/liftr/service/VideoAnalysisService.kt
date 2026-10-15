package com.liftr.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.SetSummary
import com.liftr.domain.model.TempoPrescription
import com.liftr.domain.model.FrameAnnotation
import com.liftr.domain.repository.SettingsKeys
import com.liftr.domain.repository.SettingsRepository
import com.liftr.domain.repository.VideoStorageRepository
import com.liftr.domain.usecase.AnalyzeVideoUseCase
import com.liftr.domain.value.Kilograms
import com.liftr.vision.camera.LensDistortionCorrector
import com.liftr.vision.pose.VideoFramePoseDetector
import com.liftr.vision.recording.AnnotatedVideoGenerator
import com.liftr.vision.recording.FrameCompositor
import com.liftr.vision.video.VideoFilePoseSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import com.liftr.ui.common.labelRes

/**
 * Foreground service that runs post-exercise video analysis (Feature 17).
 *
 * Runs in the foreground so analysis survives the user navigating away or
 * locking the screen. Progress is published through the companion
 * [analysisState] StateFlow, and each finished analysis through [results],
 * which keeps it until the screen that started it collects it.
 *
 * Analyses wait while a set is being recorded (see [setRecordingActive]), so
 * the recording never competes with them for the CPU and GPU.
 */
@AndroidEntryPoint
class VideoAnalysisService : LifecycleService() {

    @Inject lateinit var analyzeVideoUseCase: AnalyzeVideoUseCase
    @Inject lateinit var videoStorageRepository: VideoStorageRepository
    @Inject lateinit var annotatedVideoGenerator: AnnotatedVideoGenerator
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var lensDistortionCorrector: LensDistortionCorrector
    @Inject lateinit var logger: Logger

    sealed class AnalysisState {
        data object Idle : AnalysisState()

        /**
         * @property queued Recordings waiting behind this one
         * @property paused Waiting for the set being recorded to end
         */
        data class Analyzing(
            val progress: AnalyzeVideoUseCase.Progress,
            val queued: Int = 0,
            val paused: Boolean = false
        ) : AnalysisState()

        /**
         * @property recordedVideoPath Raw workout recording kept for in-app replay.
         * @property annotatedVideoPath Shareable annotated export (Feature 09),
         *   or null if disabled / generation failed.
         */
        data class Complete(
            val summary: SetSummary,
            val recordedVideoPath: String,
            val annotatedVideoPath: String? = null
        ) : AnalysisState()
        data class Error(val message: String) : AnalysisState()
    }

    /**
     * One queued analysis, with the calibration and tempo [createIntent] handed
     * over for it, and the [owner] its result goes back to.
     */
    private data class AnalysisRequest(
        val startId: Int,
        val owner: String,
        val videoFile: File,
        val exerciseType: ExerciseType,
        val weight: Kilograms,
        val targetReps: Int,
        val calibrationData: CalibrationData,
        val tempoPrescription: TempoPrescription?
    )

    private val requests = Channel<AnalysisRequest>(Channel.UNLIMITED)
    private val pendingRequests = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        // Single worker: analyses run strictly one at a time. Launching one
        // coroutine per start let concurrent runs interleave their writes to the
        // shared state flow, and the first to finish tore down the service (and
        // therefore lifecycleScope) out from under the others.
        lifecycleScope.launch {
            for (request in requests) {
                try {
                    // lifecycleScope dispatches to Dispatchers.Main.immediate and
                    // this service shares the app's process, so the analysis must
                    // be moved off that thread explicitly — otherwise minutes of
                    // frame decoding and ML inference block the UI and the app
                    // ANRs. Service lifecycle calls below stay on Main.
                    withContext(Dispatchers.Default) {
                        runAnalysis(
                            owner = request.owner,
                            videoFile = request.videoFile,
                            exerciseType = request.exerciseType,
                            weight = request.weight,
                            targetReps = request.targetReps,
                            calibrationData = request.calibrationData,
                            tempoPrescription = request.tempoPrescription
                        )
                    }
                } finally {
                    if (pendingRequests.decrementAndGet() == 0) {
                        _analysisState.value = AnalysisState.Idle
                        stopForegroundCompat()
                    }
                    // Matching startId: the framework keeps the service alive
                    // while any newer start is still outstanding.
                    stopSelf(request.startId)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val owner = intent?.getStringExtra(EXTRA_OWNER)
        val videoPath = intent?.getStringExtra(EXTRA_VIDEO_PATH)
        val exerciseTypeName = intent?.getStringExtra(EXTRA_EXERCISE_TYPE)
        val inputs = intent?.getStringExtra(EXTRA_INPUTS_KEY)?.let { pendingInputs.remove(it) }
        if (owner == null || videoPath == null || exerciseTypeName == null || inputs == null) {
            // Must publish an error: the screen is waiting on this recording's
            // result, and returning silently leaves it waiting forever.
            logger.w(TAG, "Missing analysis params, stopping service")
            if (owner != null) finish(owner, AnalysisState.Error("Analysis could not be started."))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val weight = intent.getFloatExtra(EXTRA_WEIGHT, 0f)
        val targetReps = intent.getIntExtra(EXTRA_TARGET_REPS, 0)
        val videoFile = File(videoPath)
        if (!videoFile.exists() || videoFile.length() <= 0L) {
            logger.e(TAG, "Recording file missing or empty: $videoPath")
            finish(owner, AnalysisState.Error("Recording file not found or empty."))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForegroundCompat(createNotification("Preparing analysis…", indeterminate = true))

        pendingRequests.incrementAndGet()
        val queued = requests.trySend(
            AnalysisRequest(
                startId = startId,
                owner = owner,
                videoFile = videoFile,
                exerciseType = runCatching { ExerciseType.valueOf(exerciseTypeName) }
                    .getOrDefault(ExerciseType.BACK_SQUAT),
                weight = Kilograms(weight.coerceAtLeast(0f)),
                targetReps = targetReps,
                calibrationData = inputs.calibrationData,
                tempoPrescription = inputs.tempoPrescription
            )
        ).isSuccess

        if (queued) {
            // The analysis already running shows how many wait behind it.
            updateAnalyzing { it.copy(queued = pendingRequests.get() - 1) }
        } else {
            logger.e(TAG, "Could not queue analysis request")
            finish(owner, AnalysisState.Error("Analysis could not be started."))
            if (pendingRequests.decrementAndGet() == 0) {
                stopForegroundCompat()
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        requests.close()
        super.onDestroy()
    }

    private suspend fun runAnalysis(
        owner: String,
        videoFile: File,
        exerciseType: ExerciseType,
        weight: Kilograms,
        targetReps: Int,
        calibrationData: CalibrationData,
        tempoPrescription: TempoPrescription?
    ) {
        try {
            showProgress(AnalyzeVideoUseCase.Progress(0, 1, 0f, 0))
            waitWhileRecording()

            // Each analysis pass creates its own detector; this one only checks
            // that the pose model loads on this device.
            val poseModelAvailable = VideoFramePoseDetector(
                context = applicationContext,
                logger = logger,
                lensDistortionCorrector = lensDistortionCorrector
            ).use { it.isAvailable() }
            if (!poseModelAvailable) {
                videoStorageRepository.deleteRecording(videoFile)
                finish(owner, AnalysisState.Error("Pose analysis isn't available on this device."))
                return
            }

            val saveAnnotated = settingsRepository
                .getBooleanSetting(SettingsKeys.SAVE_ANNOTATED_VIDEO, false).first()
            val annotations = if (saveAnnotated) mutableListOf<FrameAnnotation>() else null

            val result = analyzeVideoUseCase.invoke(
                params = AnalyzeVideoUseCase.Params(
                    videoFile = videoFile,
                    exerciseType = exerciseType,
                    weight = weight,
                    targetReps = targetReps,
                    calibrationData = calibrationData,
                    tempoPrescription = tempoPrescription
                ),
                poseSource = VideoFilePoseSource(
                    videoFile,
                    createPoseDetector = {
                        VideoFramePoseDetector(
                            context = applicationContext,
                            logger = logger,
                            lensDistortionCorrector = lensDistortionCorrector,
                            perFrame = exerciseType.isJumpExercise()
                        )
                    },
                    logger = logger,
                    beforeFrame = ::waitWhileRecording
                ),
                onProgress = { progress ->
                    showProgress(progress)
                    updateNotification(
                        "Analyzing ${progress.currentFrame}/${progress.totalFrames} frames",
                        percent = (progress.percentComplete * 100).toInt()
                    )
                },
                onFrameAnnotation = annotations?.let { sink -> { frame -> sink.add(frame) } }
            )

            result.fold(
                onSuccess = { summary ->
                    val annotatedPath = if (saveAnnotated && !annotations.isNullOrEmpty()) {
                        generateAnnotatedVideo(videoFile, exerciseType, weight, targetReps, annotations)
                    } else null
                    // Keep the raw recording for in-app replay. If an annotated
                    // export was produced, drop the raw file to save space.
                    if (annotatedPath != null) {
                        videoStorageRepository.deleteRecording(videoFile)
                    }
                    val playbackPath = annotatedPath ?: videoFile.absolutePath
                    finish(
                        owner,
                        AnalysisState.Complete(
                            summary = summary,
                            recordedVideoPath = playbackPath,
                            annotatedVideoPath = annotatedPath
                        )
                    )
                    logger.i(TAG, "Analysis complete (playback=$playbackPath, annotated=$annotatedPath)")
                },
                onFailure = { error ->
                    videoStorageRepository.deleteRecording(videoFile)
                    finish(owner, AnalysisState.Error(error.message ?: "Analysis failed"))
                    logger.e(TAG, "Analysis failed", error)
                }
            )
        } catch (e: Throwable) {
            videoStorageRepository.deleteRecording(videoFile)
            finish(owner, AnalysisState.Error(e.message ?: "Unexpected error"))
            logger.e(TAG, "Analysis crashed", e)
        }
        // Per-run resources (pose detectors) are closed by each analysis pass.
        // Tearing down the service is the worker loop's job, once the whole
        // queue is drained — doing it per run cancelled analyses that were
        // still waiting behind this one.
    }

    private fun showProgress(progress: AnalyzeVideoUseCase.Progress) {
        _analysisState.value = AnalysisState.Analyzing(
            progress,
            queued = pendingRequests.get() - 1,
            paused = recordingActive.value
        )
    }

    private fun updateAnalyzing(change: (AnalysisState.Analyzing) -> AnalysisState.Analyzing) {
        _analysisState.update { if (it is AnalysisState.Analyzing) change(it) else it }
    }

    /**
     * Suspends while a set is being recorded: decoding and the pose model
     * would compete with the recording for the CPU and GPU, and a dropped
     * frame costs a jump's takeoff timing.
     */
    private suspend fun waitWhileRecording() {
        if (!recordingActive.value) return
        logger.i(TAG, "Recording in progress, analysis paused")
        updateAnalyzing { it.copy(paused = true) }
        updateNotificationIndeterminate("Paused while recording")
        recordingActive.first { !it }
        updateAnalyzing { it.copy(paused = false) }
        logger.i(TAG, "Recording ended, analysis resumed")
    }

    /**
     * Second pass: burn the skeleton + HUD into a shareable MP4 (Feature 09).
     * Returns the annotated file path, or null on failure (never throws).
     *
     * A set recorded meanwhile stops the render, which starts over once the
     * recording ends: pausing it instead would keep a hardware encoder the
     * camera needs.
     */
    private suspend fun generateAnnotatedVideo(
        videoFile: File,
        exerciseType: ExerciseType,
        weight: Kilograms,
        targetReps: Int,
        annotations: List<FrameAnnotation>
    ): String? {
        while (true) {
            waitWhileRecording()
            updateNotificationIndeterminate("Rendering annotated video…")
            val outputFile = videoStorageRepository.getAnnotatedFile(
                exerciseType, System.currentTimeMillis()
            )
            val rendered = annotatedVideoGenerator.generate(
                AnnotatedVideoGenerator.Params(
                    sourceVideo = videoFile,
                    outputFile = outputFile,
                    annotations = annotations,
                    header = FrameCompositor.Header(
                        exerciseLabel = getString(exerciseType.labelRes),
                        weightKg = weight.value,
                        targetReps = targetReps
                    )
                ),
                abortIf = { recordingActive.value }
            )
            if (rendered != null || !recordingActive.value) return rendered?.absolutePath
            logger.i(TAG, "Recording started, annotated video will render again after it")
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Video Analysis", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Workout video analysis progress" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        text: String,
        percent: Int = 0,
        indeterminate: Boolean = false
    ): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Analyzing workout")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .build()
    }

    private fun updateNotification(text: String, percent: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(text, percent = percent))
    }

    private fun updateNotificationIndeterminate(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(text, indeterminate = true))
    }

    companion object {
        private const val TAG = "VideoAnalysisService"
        private const val CHANNEL_ID = "video_analysis"
        private const val NOTIFICATION_ID = 1001

        private const val EXTRA_VIDEO_PATH = "video_path"
        private const val EXTRA_EXERCISE_TYPE = "exercise_type"
        private const val EXTRA_WEIGHT = "weight"
        private const val EXTRA_TARGET_REPS = "target_reps"
        private const val EXTRA_OWNER = "owner"
        private const val EXTRA_INPUTS_KEY = "inputs_key"

        private val _analysisState = MutableStateFlow<AnalysisState>(AnalysisState.Idle)

        /** Progress of the queue: [AnalysisState.Analyzing] while it runs, else [AnalysisState.Idle]. */
        val analysisState: StateFlow<AnalysisState> = _analysisState.asStateFlow()

        /** A finished analysis waiting for the screen that started it. */
        private class Finished(val owner: String, val state: AnalysisState)

        private val finished = MutableStateFlow<List<Finished>>(emptyList())
        private val releasedOwners = ConcurrentHashMap.newKeySet<String>()

        /**
         * Each finished analysis [owner] started, [AnalysisState.Complete] or
         * [AnalysisState.Error], in queue order and delivered once. Results wait
         * until collected, so none is lost while the screen isn't listening,
         * none overwrites another that finished just before it, and none
         * reaches a different workout.
         */
        fun results(owner: String): Flow<AnalysisState> = finished
            .map { all -> all.filter { it.owner == owner } }
            .filter { it.isNotEmpty() }
            .transform { mine ->
                finished.update { all -> all.filterNot { it in mine } }
                mine.forEach { emit(it.state) }
            }

        /**
         * [owner] is gone (its screen closed): drop its results, now and for
         * analyses still queued, with their videos, which nobody can open.
         */
        fun release(owner: String) {
            releasedOwners.add(owner)
            var dropped = emptyList<Finished>()
            finished.update { all ->
                dropped = all.filter { it.owner == owner }
                all - dropped.toSet()
            }
            dropped.forEach { deleteVideos(it.state) }
        }

        private fun finish(owner: String, result: AnalysisState) {
            if (owner in releasedOwners) {
                deleteVideos(result)
            } else {
                finished.update { it + Finished(owner, result) }
            }
        }

        private fun deleteVideos(result: AnalysisState) {
            if (result is AnalysisState.Complete) {
                runCatching { File(result.recordedVideoPath).delete() }
            }
        }

        private val recordingActive = MutableStateFlow(false)

        /** Tell queued and running analyses whether a set is being recorded; they wait while it is. */
        fun setRecordingActive(active: Boolean) {
            recordingActive.value = active
        }

        private class Inputs(
            val calibrationData: CalibrationData,
            val tempoPrescription: TempoPrescription?
        )

        // Passed out-of-band because CalibrationData/TempoPrescription are
        // awkward to marshal through Intent extras. Keyed per request, so a
        // queue draining between createIntent and onStartCommand can't drop them.
        private val pendingInputs = ConcurrentHashMap<String, Inputs>()

        /** @param owner Who [results] hands this analysis' result back to. */
        fun createIntent(
            context: Context,
            owner: String,
            videoFile: File,
            exerciseType: ExerciseType,
            weight: Kilograms,
            targetReps: Int,
            calibrationData: CalibrationData,
            tempoPrescription: TempoPrescription? = null
        ): Intent {
            val inputsKey = UUID.randomUUID().toString()
            pendingInputs[inputsKey] = Inputs(calibrationData, tempoPrescription)
            return Intent(context, VideoAnalysisService::class.java).apply {
                putExtra(EXTRA_OWNER, owner)
                putExtra(EXTRA_INPUTS_KEY, inputsKey)
                putExtra(EXTRA_VIDEO_PATH, videoFile.absolutePath)
                putExtra(EXTRA_EXERCISE_TYPE, exerciseType.name)
                putExtra(EXTRA_WEIGHT, weight.value)
                putExtra(EXTRA_TARGET_REPS, targetReps)
            }
        }
    }
}
