package com.liftr.vision.camera

import android.annotation.SuppressLint
import android.content.Context
import android.util.Range
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.core.ExperimentalSessionConfig
import androidx.camera.core.Preview
import androidx.camera.video.ExperimentalHighSpeedVideo
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.repository.VideoStorageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps CameraX [VideoCapture] / [Recording] for the post-exercise analysis
 * pipeline (Feature 17). Records muted H.264 MP4 at HD (720p) into app-private
 * storage; the file is later decoded frame-by-frame by the analysis pipeline.
 */
@Singleton
class VideoRecordingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoStorageRepository: VideoStorageRepository,
    private val logger: Logger
) {
    private var activeRecording: Recording? = null
    private var currentOutputFile: File? = null
    // CameraX finalizes the MP4 asynchronously after [Recording.stop]. We must
    // wait for [VideoRecordEvent.Finalize] before handing the file to analysis.
    private var finalizeDeferred: CompletableDeferred<File?>? = null

    /**
     * Create a [VideoCapture] use case configured for workout recording.
     * Prefers HD (720p), falling back to the nearest available quality, at
     * the highest constant frame rate [cameraInfo] supports: fast movements
     * (jump flight, snatch pulls) are timed from frames, so more is better.
     */
    fun createVideoCaptureUseCase(cameraInfo: CameraInfo?): VideoCapture<Recorder> {
        val qualitySelector = QualitySelector.from(
            Quality.HD,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()

        val builder = VideoCapture.Builder(recorder)
        highestConstantFrameRate(cameraInfo)?.let { range ->
            logger.i(TAG, "Recording at ${range.upper} fps")
            builder.setTargetFrameRate(range)
        }
        return builder.build()
    }

    /** A high-speed recording session: bind [config], record with [videoCapture]. */
    @OptIn(ExperimentalSessionConfig::class, ExperimentalHighSpeedVideo::class)
    class HighSpeedSession(
        val videoCapture: VideoCapture<Recorder>,
        val config: HighSpeedVideoSessionConfig,
        val fps: Int
    )

    /**
     * A high-speed session for [cameraInfo] at its lowest high-speed frame
     * rate (120 fps when offered), or null when the camera has no high-speed
     * mode (e.g. most front cameras). Records real-time high-frame-rate
     * video, not slow motion, so frame timestamps stay in real time for the
     * analysis.
     *
     * A high-speed session binds only preview and video: no ImageAnalysis, so
     * the live rep counter can't run alongside it.
     */
    @OptIn(ExperimentalSessionConfig::class, ExperimentalHighSpeedVideo::class)
    fun createHighSpeedSession(cameraInfo: CameraInfo, preview: Preview): HighSpeedSession? {
        val capabilities = Recorder.getHighSpeedVideoCapabilities(cameraInfo) ?: run {
            logger.i(TAG, "No high-speed video on this camera")
            return null
        }
        val qualities = capabilities.getSupportedQualities(DynamicRange.SDR)
        // 720p first: it is where devices offer their highest rates (240 fps).
        val quality = listOf(Quality.HD, Quality.FHD).firstOrNull { it in qualities }
            ?: qualities.firstOrNull()
            ?: run {
                logger.i(TAG, "High-speed video has no SDR quality")
                return null
            }

        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build()
        val videoCapture = VideoCapture.withOutput(recorder)
        val probe = HighSpeedVideoSessionConfig.Builder(videoCapture).setPreview(preview).build()
        val ranges = cameraInfo.getSupportedFrameRateRanges(probe)
        // Slowest high-speed rate: 120 over 240 fps keeps twice the exposure
        // time, so the image stays brighter indoors.
        val range = ranges.filter { it.upper >= MIN_HIGH_SPEED_FPS }.minByOrNull { it.upper }
            ?: ranges.maxByOrNull { it.upper } ?: run {
            logger.i(TAG, "High-speed video at $quality has no frame rate range")
            return null
        }
        logger.d(TAG, "High-speed qualities $qualities, ranges at $quality: $ranges")

        val config = HighSpeedVideoSessionConfig.Builder(videoCapture)
            .setPreview(preview)
            .setFrameRateRange(range)
            .build()
        logger.i(TAG, "High-speed recording at ${range.upper} fps ($quality)")
        return HighSpeedSession(videoCapture, config, range.upper)
    }

    /**
     * The fastest fixed range (lower == upper), so frames stay evenly spaced
     * and the camera can't drop the rate in dim light; null leaves the default.
     */
    private fun highestConstantFrameRate(cameraInfo: CameraInfo?): Range<Int>? =
        cameraInfo?.supportedFrameRateRanges
            ?.filter { it.lower == it.upper }
            ?.maxByOrNull { it.upper }

    /**
     * Start recording to an auto-generated file.
     *
     * Audio is intentionally NOT enabled — the recording is only used for pose
     * analysis, so no microphone permission is requested.
     *
     * @param videoCapture VideoCapture use case (must be bound to camera lifecycle)
     * @param exerciseType Exercise being performed (encoded in the filename)
     * @param onEvent Callback for CameraX recording events (start, finalize, error)
     */
    @SuppressLint("MissingPermission")
    fun startRecording(
        videoCapture: VideoCapture<Recorder>,
        exerciseType: ExerciseType,
        onEvent: (VideoRecordEvent) -> Unit
    ): Boolean {
        if (activeRecording != null) {
            logger.w(TAG, "startRecording called while a recording is already active")
            return false
        }

        val outputFile = videoStorageRepository.getRecordingFile(
            exerciseType, System.currentTimeMillis()
        )
        // Capture this recording's file and deferred in the callback closure.
        // Reading them back from the shared fields would let a late Finalize
        // from a previous (timed-out) recording clobber the current one.
        val deferred = CompletableDeferred<File?>()
        currentOutputFile = outputFile
        finalizeDeferred = deferred

        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        activeRecording = videoCapture.output
            .prepareRecording(context, outputOptions)
            .start(context.mainExecutor) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    if (event.hasError()) {
                        logger.e(TAG, "Recording finalize error code=${event.error}")
                        deferred.complete(null)
                    } else {
                        val ready = outputFile.takeIf { it.exists() && it.length() > 0 }
                        if (ready == null) {
                            logger.e(TAG, "Recording finalized but file is missing or empty: ${outputFile.absolutePath}")
                        } else {
                            logger.i(TAG, "Recording finalized: ${ready.absolutePath} (${ready.length()} bytes)")
                        }
                        deferred.complete(ready)
                    }
                }
                onEvent(event)
            }

        logger.i(TAG, "Recording started: ${outputFile.absolutePath}")
        return true
    }

    /**
     * Stop the active recording and wait for CameraX to finalize the MP4.
     *
     * @return The finalized output file, or null if no recording was active,
     *   finalize failed, or the file is missing/empty.
     */
    suspend fun stopRecording(): File? {
        val recording = activeRecording ?: return null
        val deferred = finalizeDeferred ?: return null
        recording.stop()
        activeRecording = null
        currentOutputFile = null
        finalizeDeferred = null
        return try {
            withTimeout(FINALIZE_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            logger.e(TAG, "Timed out waiting for recording finalize", e)
            null
        }
    }

    /**
     * Stop the active recording without waiting for finalization.
     *
     * Use this from teardown paths that run on the main thread (e.g.
     * `ViewModel.onCleared`). CameraX delivers [VideoRecordEvent.Finalize] on
     * the main executor, so blocking the main thread to await it — as
     * `runBlocking { stopRecording() }` would — deadlocks until the timeout.
     */
    fun abandonRecording() {
        val recording = activeRecording ?: return
        logger.i(TAG, "Abandoning active recording (no finalize wait)")
        recording.stop()
        activeRecording = null
        currentOutputFile = null
        // Release anyone still awaiting finalization; the file is not usable.
        finalizeDeferred?.complete(null)
        finalizeDeferred = null
    }

    /** Whether a recording is currently active. */
    fun isRecording(): Boolean = activeRecording != null

    companion object {
        private const val TAG = "VideoRecordingManager"
        private const val FINALIZE_TIMEOUT_MS = 5_000L
        private const val MIN_HIGH_SPEED_FPS = 120
    }
}
