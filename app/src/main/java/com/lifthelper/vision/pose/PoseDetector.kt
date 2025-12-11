package com.lifthelper.vision.pose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.lifthelper.domain.model.PoseKeypoint
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.util.Constants
import com.lifthelper.util.Constants.TAG_POSE
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PoseDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var poseLandmarker: PoseLandmarker? = null
    private var latestResult: PoseLandmarkerResult? = null
    private var lastProcessedTimestamp = 0L
    private var isProcessing = false
    private val minFrameIntervalMs = 66L // ~15 FPS max - more aggressive for buffer management

    private var detectionCount = 0
    private var skippedFrames = 0
    private var currentModelPath: String = Constants.POSE_MODEL_WORKOUT

    init {
        Log.d(TAG_POSE, "PoseDetector initializing...")
        setupPoseLandmarker(Constants.POSE_MODEL_WORKOUT)
    }

    /**
     * Setup pose landmarker with specific model
     *
     * @param modelPath Path to MediaPipe model file
     *   - POSE_MODEL_LITE: Fast, for real-time workouts
     *   - POSE_MODEL_FULL: Balanced accuracy and speed
     *   - POSE_MODEL_HEAVY: Most accurate, for calibration
     */
    private fun setupPoseLandmarker(modelPath: String = currentModelPath) {
        try {
            // Close existing landmarker if switching models
            if (currentModelPath != modelPath) {
                poseLandmarker?.close()
                currentModelPath = modelPath
            }

            Log.i(TAG_POSE, "Initializing PoseLandmarker with model: $modelPath")
            Timber.d("Initializing PoseLandmarker with model: $modelPath")

            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelPath)
                .setDelegate(Delegate.GPU)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(Constants.POSE_MIN_DETECTION_CONFIDENCE)
                .setMinPosePresenceConfidence(Constants.POSE_MIN_PRESENCE_CONFIDENCE)
                .setMinTrackingConfidence(Constants.POSE_MIN_TRACKING_CONFIDENCE)
                .setResultListener { result, _ ->
                    detectionCount++
                    latestResult = result
                    isProcessing = false
                    if (detectionCount % 30 == 0) {
                        Log.d(TAG_POSE, "Pose detections: $detectionCount, skipped frames: $skippedFrames")
                    }
                }
                .setErrorListener { error ->
                    Log.e(TAG_POSE, "PoseDetector callback error: ${error.message}")
                    Timber.e("PoseDetector callback error: ${error.message}")
                    isProcessing = false
                }
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            Log.i(TAG_POSE, "✅ PoseLandmarker initialized successfully with GPU delegate")
            Timber.d("✅ PoseLandmarker initialized successfully with GPU delegate")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to initialize PoseLandmarker")
            // Try with CPU delegate as fallback
            try {
                Timber.w("Retrying with CPU delegate...")
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(modelPath)
                    .setDelegate(Delegate.CPU)
                    .build()

                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(Constants.POSE_MIN_DETECTION_CONFIDENCE)
                    .setMinPosePresenceConfidence(Constants.POSE_MIN_PRESENCE_CONFIDENCE)
                    .setMinTrackingConfidence(Constants.POSE_MIN_TRACKING_CONFIDENCE)
                    .setResultListener { result, _ ->
                        latestResult = result
                        isProcessing = false
                    }
                    .setErrorListener { error ->
                        Timber.e("PoseDetector error: ${error.message}")
                        isProcessing = false
                    }
                    .build()

                poseLandmarker = PoseLandmarker.createFromOptions(context, options)
                Timber.d("✅ PoseLandmarker initialized with CPU delegate")
            } catch (cpuError: Exception) {
                Timber.e(cpuError, "❌ Failed to initialize PoseLandmarker with CPU delegate")
            }
        }
    }

    fun detectPose(imageProxy: ImageProxy, timestamp: Long): PoseResult? {
        try {
            if (poseLandmarker == null) {
                Timber.w("PoseLandmarker not initialized")
                return null
            }

            // Get actual image dimensions
            val imageWidth = imageProxy.width
            val imageHeight = imageProxy.height

            // Skip frame if we're still processing or too soon since last frame
            val timeSinceLastFrame = timestamp - lastProcessedTimestamp
            if (isProcessing || timeSinceLastFrame < minFrameIntervalMs) {
                // Return latest cached result without processing new frame
                return latestResult?.let { convertToPoseResult(it, timestamp, imageWidth, imageHeight) }
            }

            // Mark as processing and update timestamp
            isProcessing = true
            lastProcessedTimestamp = timestamp

            // Convert ImageProxy to MPImage
            val bitmap = imageProxyToBitmap(imageProxy)

            // Log bitmap dimensions to debug size mismatch
            if (detectionCount % 90 == 0) {
                Log.d(TAG_POSE, "MediaPipe receiving: ImageProxy=${imageWidth}x${imageHeight}, Bitmap=${bitmap.width}x${bitmap.height}")
            }

            val mpImage = BitmapImageBuilder(bitmap).build()

            // Detect pose (async, result comes via callback)
            poseLandmarker?.detectAsync(mpImage, timestamp)

            // Return latest result (may be from previous frame)
            return latestResult?.let { convertToPoseResult(it, timestamp, imageWidth, imageHeight) }
        } catch (e: Exception) {
            Timber.e(e, "Pose detection failed")
            isProcessing = false
            return null
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val width = imageProxy.width
        val height = imageProxy.height

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        try {
            // For RGBA_8888 format from CameraX
            val buffer = imageProxy.planes[0].buffer
            val pixelStride = imageProxy.planes[0].pixelStride
            val rowStride = imageProxy.planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            buffer.rewind()

            // Simple copy for RGBA_8888
            if (pixelStride == 4 && rowPadding == 0) {
                // Direct copy - faster path
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                // Manual conversion with padding
                val pixels = IntArray(width * height)
                var offset = 0

                for (row in 0 until height) {
                    for (col in 0 until width) {
                        if (buffer.remaining() >= 4) {
                            val r = buffer.get().toInt() and 0xFF
                            val g = buffer.get().toInt() and 0xFF
                            val b = buffer.get().toInt() and 0xFF
                            val a = buffer.get().toInt() and 0xFF

                            pixels[offset++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    // Skip row padding
                    if (rowPadding > 0 && buffer.remaining() >= rowPadding) {
                        buffer.position(buffer.position() + rowPadding)
                    }
                }

                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error converting ImageProxy to Bitmap")
        }

        return bitmap
    }

    private fun convertToPoseResult(
        landmarkerResult: PoseLandmarkerResult,
        timestamp: Long,
        imageWidth: Int,
        imageHeight: Int
    ): PoseResult? {
        if (landmarkerResult.landmarks().isEmpty()) return null

        val landmarks = landmarkerResult.landmarks()[0].map { landmark ->
            PoseKeypoint(
                x = landmark.x(),
                y = landmark.y(),
                z = landmark.z(),
                visibility = landmark.visibility().orElse(0f)
            )
        }

        // Log image dimensions for debugging orientation handling
        Log.d(Constants.TAG_POSE,
            "PoseResult created: ${imageWidth}x${imageHeight}, " +
            "orientation: ${if (imageHeight > imageWidth) "portrait" else "landscape"}")

        return PoseResult(landmarks, timestamp, imageWidth, imageHeight)
    }

    /**
     * Switch to a different pose detection model
     *
     * Use this to switch between fast (workout) and accurate (calibration) models
     *
     * @param modelPath Path to the model file (use Constants.POSE_MODEL_*)
     */
    @Synchronized
    fun switchModel(modelPath: String) {
        if (modelPath == currentModelPath) {
            Log.d(TAG_POSE, "Already using model: $modelPath")
            return
        }

        Log.i(TAG_POSE, "Switching model: $currentModelPath → $modelPath")
        setupPoseLandmarker(modelPath)
    }

    /**
     * Get currently active model
     */
    fun getCurrentModel(): String = currentModelPath

    @Synchronized
    fun close() {
        try {
            isProcessing = false
            poseLandmarker?.close()
            poseLandmarker = null
            latestResult = null
            Timber.d("✅ PoseDetector closed")
        } catch (e: Exception) {
            Timber.e(e, "Error closing PoseDetector")
        }
    }
}
