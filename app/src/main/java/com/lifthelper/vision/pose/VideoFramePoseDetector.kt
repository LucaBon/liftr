package com.lifthelper.vision.pose

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.PoseKeypoint
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.util.Constants
import com.lifthelper.vision.camera.LensDistortionCorrector
import java.io.Closeable

/**
 * Pose detector for offline (post-exercise) video analysis (Feature 17).
 *
 * Unlike the real-time [PoseDetector], this:
 * - uses [RunningMode.VIDEO] (synchronous, returns the result directly)
 * - uses the heavy model by default for maximum landmark accuracy
 * - accepts a [Bitmap] directly (frames come from [com.lifthelper.vision.video.VideoFrameExtractor])
 * - has no frame throttling / caching / OneEuro smoothing (the bidirectional
 *   velocity pass handles smoothing with full-set context)
 * - is NOT a singleton — create one per analysis session and [close] it after.
 *
 * Timestamps passed to [detectPose] MUST be monotonically increasing.
 */
class VideoFramePoseDetector(
    private val context: Context,
    private val logger: Logger,
    private val lensDistortionCorrector: LensDistortionCorrector,
    modelPath: String = Constants.POSE_MODEL_HEAVY
) : Closeable {

    private var poseLandmarker: PoseLandmarker? = null
    private var detectionCount = 0

    init {
        logger.i(TAG, "Initializing with model: $modelPath (RunningMode.VIDEO)")
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
                .setRunningMode(RunningMode.VIDEO)
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
        val result = landmarker.detectForVideo(mpImage, timestampMs)

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

    override fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
        logger.d(TAG, "Closed after $detectionCount detections")
    }

    companion object {
        private const val TAG = "VideoFramePoseDetector"
    }
}
