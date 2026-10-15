package com.liftr.vision.pose

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.util.Constants
import com.liftr.vision.camera.LensDistortionCorrector
import java.io.Closeable

/**
 * Pose detector for offline (post-exercise) video analysis (Feature 17).
 *
 * Unlike the real-time [PoseDetector], this:
 * - uses [RunningMode.VIDEO] (synchronous, returns the result directly), or
 *   [RunningMode.IMAGE] with [perFrame]
 * - uses the heavy model by default for maximum landmark accuracy
 * - accepts a [Bitmap] directly (frames come from [com.liftr.vision.video.VideoFrameExtractor])
 * - has no frame throttling / caching / OneEuro smoothing (the bidirectional
 *   velocity pass handles smoothing with full-set context)
 * - is NOT a singleton — create one per analysis session and [close] it after.
 *
 * Timestamps passed to [detectPose] MUST be monotonically increasing.
 *
 * @param perFrame Detect every frame on its own, without VIDEO mode's tracking
 *   and temporal filtering. That filtering bends a jump's flight away from a
 *   parabola by a couple of centimeters, the same way in every jump, which
 *   biases the takeoff velocity and the free-fall checks. Per-frame detection
 *   is noisier and misses a few more frames, but its errors are random.
 */
class VideoFramePoseDetector(
    private val context: Context,
    private val logger: Logger,
    private val lensDistortionCorrector: LensDistortionCorrector,
    modelPath: String = Constants.POSE_MODEL_HEAVY,
    private val perFrame: Boolean = false
) : Closeable {

    private var poseLandmarker: PoseLandmarker? = null
    private var detectionCount = 0

    init {
        logger.i(TAG, "Initializing with model: $modelPath (${runningMode()})")
        poseLandmarker = createLandmarker(modelPath, Delegate.GPU)
            ?: createLandmarker(modelPath, Delegate.CPU)
        if (poseLandmarker == null) {
            logger.e(TAG, "Failed to create PoseLandmarker (GPU and CPU)")
        } else {
            logger.i(TAG, "PoseLandmarker initialized")
        }
    }

    fun isAvailable(): Boolean = poseLandmarker != null

    private fun createLandmarker(modelPath: String, delegate: Delegate): PoseLandmarker? {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelPath)
                .setDelegate(delegate)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(runningMode())
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(Constants.POSE_MIN_DETECTION_CONFIDENCE)
                .setMinPosePresenceConfidence(Constants.POSE_MIN_PRESENCE_CONFIDENCE)
                .setMinTrackingConfidence(Constants.POSE_MIN_TRACKING_CONFIDENCE)
                .build()

            PoseLandmarker.createFromOptions(context, options).also {
                logger.d(TAG, "Created with ${delegate.name} delegate")
            }
        } catch (t: Throwable) {
            logger.w(TAG, "Failed with ${delegate.name} delegate: ${t.message}")
            null
        }
    }

    /**
     * Detect the pose in a single video frame (synchronous, blocking).
     *
     * @param bitmap The video frame
     * @param timestampMs Monotonically increasing frame timestamp in milliseconds
     * @return PoseResult with lens-corrected landmarks, or null if no pose detected
     */
    fun detectPose(bitmap: Bitmap, timestampMs: Long): PoseResult? {
        val landmarker = poseLandmarker ?: return null

        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = if (perFrame) landmarker.detect(mpImage) else landmarker.detectForVideo(mpImage, timestampMs)

        detectionCount++
        if (detectionCount % 100 == 0) {
            logger.d(TAG, "Processed $detectionCount frames")
        }

        if (result.landmarks().isEmpty()) return null

        val landmarks = result.landmarks()[0].map { landmark ->
            PoseKeypoint(
                x = landmark.x(),
                y = landmark.y(),
                z = landmark.z(),
                visibility = landmark.visibility().orElse(0f)
            )
        }

        val correctedLandmarks = lensDistortionCorrector.correctLandmarks(
            landmarks, bitmap.width, bitmap.height
        )

        val isPortrait = bitmap.height > bitmap.width

        return PoseResult(
            correctedLandmarks, timestampMs, bitmap.width, bitmap.height, isPortrait
        )
    }

    private fun runningMode() = if (perFrame) RunningMode.IMAGE else RunningMode.VIDEO

    override fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
        logger.d(TAG, "Closed after $detectionCount detections")
    }

    companion object {
        private const val TAG = "VideoFramePoseDetector"
    }
}
