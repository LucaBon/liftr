package com.lifthelper.vision.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.lifthelper.domain.error.LiftHelperError
import com.lifthelper.domain.error.PoseErrorCode
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.PoseKeypoint
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.vision.IPoseDetector
import com.lifthelper.util.Constants
import com.lifthelper.vision.calculator.LandmarkOneEuroFilter
import com.lifthelper.vision.camera.LensDistortionCorrector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PoseDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
    private val lensDistortionCorrector: LensDistortionCorrector,
    private val landmarkFilter: LandmarkOneEuroFilter
) : IPoseDetector {
    companion object {
        private const val TAG = "PoseDetector"
    }
    /**
     * Cached already-filtered PoseResult with its associated dimensions.
     * Filtering happens once per MediaPipe callback so the OneEuro filter never
     * re-smooths stale landmarks when we return the cached value on skipped frames.
     */
    private data class CachedResult(
        val poseResult: PoseResult?,
        val width: Int,
        val height: Int
    )

    private var poseLandmarker: PoseLandmarker? = null
    // Thread-safe: accessed from MediaPipe callback thread and camera processing thread (BUG-A fix)
    private val cachedResult = AtomicReference<CachedResult?>(null)
    @Volatile private var lastProcessedTimestamp = 0L
    @Volatile private var isProcessing = false
    private val minFrameIntervalMs = 66L // ~15 FPS max - more aggressive for buffer management

    private var detectionCount = 0
    private var skippedFrames = 0
    private var currentModelPath: String = Constants.POSE_MODEL_WORKOUT

    init {
        logger.d(TAG, "PoseDetector initializing...")
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
                // Reset smoothing: old filter state would have stale dt after the gap.
                landmarkFilter.reset()
                cachedResult.set(null)
            }

            logger.i(TAG, "Initializing PoseLandmarker with model: $modelPath")

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
                    // Dimensions are pre-set by processImageProxy before detectAsync.
                    val current = cachedResult.get()
                    val w = current?.width ?: 0
                    val h = current?.height ?: 0
                    val filtered = if (w > 0 && h > 0) {
                        buildFilteredPoseResult(result, lastProcessedTimestamp, w, h)
                    } else null
                    cachedResult.set(CachedResult(filtered, w, h))
                    isProcessing = false
                    if (detectionCount % 30 == 0) {
                        logger.d(TAG, "Pose detections: $detectionCount, skipped frames: $skippedFrames")
                    }
                }
                .setErrorListener { error ->
                    logger.e(TAG, "PoseDetector callback error: ${error.message}")
                    isProcessing = false
                }
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            logger.i(TAG, "✅ PoseLandmarker initialized successfully with GPU delegate")
            logger.d(TAG, "✅ PoseLandmarker initialized successfully with GPU delegate")
        } catch (e: Exception) {
            logger.e(TAG, "❌ Failed to initialize PoseLandmarker")
            // Try with CPU delegate as fallback
            try {
                logger.w(TAG, "Retrying with CPU delegate...")
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
                        val current = cachedResult.get()
                        val w = current?.width ?: 0
                        val h = current?.height ?: 0
                        val filtered = if (w > 0 && h > 0) {
                            buildFilteredPoseResult(result, lastProcessedTimestamp, w, h)
                        } else null
                        cachedResult.set(CachedResult(filtered, w, h))
                        isProcessing = false
                    }
                    .setErrorListener { error ->
                        logger.e(TAG, "PoseDetector error: ${error.message}")
                        isProcessing = false
                    }
                    .build()

                poseLandmarker = PoseLandmarker.createFromOptions(context, options)
                logger.d(TAG, "✅ PoseLandmarker initialized with CPU delegate")
            } catch (cpuError: Exception) {
                logger.e(TAG, "Failed to initialize PoseLandmarker with CPU delegate", cpuError)
                throw LiftHelperError.PoseDetectionError(
                    "Failed to initialize pose detector",
                    PoseErrorCode.MODEL_NOT_LOADED
                )
            }
        }
    }

    override fun detectPose(frame: com.lifthelper.domain.model.CameraFrame): PoseResult? {
        return frame.data.process(object : com.lifthelper.domain.model.FrameProcessor<PoseResult?> {
            override fun process(data: Any): PoseResult? {
                return processImageProxy(data as ImageProxy, frame.timestamp)
            }
        })
    }

    /**
     * Internal method to process ImageProxy frame data.
     * This keeps the Android-specific logic isolated.
     */
    private fun processImageProxy(imageProxy: ImageProxy, timestamp: Long): PoseResult? {
        var originalBitmap: Bitmap? = null
        var rotatedBitmap: Bitmap? = null

        try {
            if (poseLandmarker == null) {
                logger.w(TAG, "PoseLandmarker not initialized")
                return null
            }

            // Get image rotation from CameraX (0°, 90°, 180°, or 270°)
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            // Get original image dimensions (before rotation)
            val originalWidth = imageProxy.width
            val originalHeight = imageProxy.height

            // Skip frame if we're still processing or too soon since last frame
            val timeSinceLastFrame = timestamp - lastProcessedTimestamp
            if (isProcessing || timeSinceLastFrame < minFrameIntervalMs) {
                skippedFrames++
                // Return latest cached (already-filtered) result, stamped with current frame time.
                val cached = cachedResult.get()
                return cached?.poseResult?.copy(timestamp = timestamp)
            }

            // Mark as processing and update timestamp
            isProcessing = true
            lastProcessedTimestamp = timestamp

            // Convert ImageProxy to Bitmap
            originalBitmap = imageProxyToBitmap(imageProxy)

            // Rotate bitmap to upright orientation for MediaPipe
            // MediaPipe expects upright images (person's head at top)
            val (finalBitmap, finalWidth, finalHeight) = if (rotationDegrees != 0) {
                rotatedBitmap = rotateBitmap(originalBitmap, rotationDegrees.toFloat())
                // After rotation, width and height are swapped for 90° and 270° rotations
                val newWidth = rotatedBitmap.width
                val newHeight = rotatedBitmap.height

                Triple(rotatedBitmap, newWidth, newHeight)
            } else {
                // No rotation needed, use original bitmap
                rotatedBitmap = originalBitmap
                Triple(originalBitmap, originalWidth, originalHeight)
            }

            // Log rotation info to debug portrait mode issues
            if (detectionCount % 90 == 0) {
                logger.d(TAG, "Image rotation: ${rotationDegrees}°, " +
                        "Original: ${originalWidth}x${originalHeight}, " +
                        "Rotated: ${finalBitmap.width}x${finalBitmap.height}, " +
                        "Final dimensions for PoseResult: ${finalWidth}x${finalHeight}")
            }

            val mpImage = BitmapImageBuilder(finalBitmap).build()

            // Pre-set dimensions so the async callback can use them (BUG-A/E fix).
            // Always overwrite dimensions, even on the very first detect when no cache exists.
            val current = cachedResult.get()
            cachedResult.set(CachedResult(current?.poseResult, finalWidth, finalHeight))

            // Detect pose (async, result comes via callback)
            poseLandmarker?.detectAsync(mpImage, timestamp)

            // Return latest cached (already-filtered) result, stamped with current frame time.
            val cached = cachedResult.get()
            return cached?.poseResult?.copy(timestamp = timestamp)
        } catch (e: Exception) {
            logger.e(TAG, "Pose detection failed", e)
            isProcessing = false
            return null
        } finally {
            // Always recycle bitmaps to prevent memory leaks
            // If rotation occurred, we have two separate bitmaps to recycle
            // If no rotation, originalBitmap and rotatedBitmap reference the same object
            if (rotatedBitmap != null && rotatedBitmap != originalBitmap) {
                rotatedBitmap.recycle()
            }
            originalBitmap?.recycle()
        }
    }

    /**
     * Rotate a bitmap by the specified angle.
     *
     * This is critical for portrait mode where the camera sensor provides
     * landscape images that need to be rotated 90° for MediaPipe to detect
     * upright poses correctly.
     *
     * @param source The original bitmap
     * @param angle Rotation angle in degrees (typically 0°, 90°, 180°, or 270°)
     * @return Rotated bitmap (may be the same instance if angle is 0)
     */
    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        if (angle == 0f) {
            return source
        }

        val matrix = Matrix()
        matrix.postRotate(angle)

        return try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to rotate bitmap by $angle degrees", e)
            source  // Return original on failure
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
            logger.e(TAG, "Error converting ImageProxy to Bitmap")
        }

        return bitmap
    }

    /**
     * Convert raw MediaPipe landmarks to a PoseResult, applying lens distortion
     * correction followed by a OneEuro filter to reduce jitter (especially under
     * self-occlusion like the bottom of a squat). Called once per fresh detection.
     */
    private fun buildFilteredPoseResult(
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

        val correctedLandmarks = lensDistortionCorrector.correctLandmarks(
            landmarks,
            imageWidth,
            imageHeight
        )
        val smoothedLandmarks = landmarkFilter.apply(correctedLandmarks, timestamp)

        val isPortrait = imageHeight > imageWidth

        logger.d(TAG,
            "PoseResult created: ${imageWidth}x${imageHeight}, " +
            "orientation: ${if (isPortrait) "portrait" else "landscape"}, " +
            "lens correction: ${lensDistortionCorrector.getCorrectionMode()}")

        return PoseResult(smoothedLandmarks, timestamp, imageWidth, imageHeight, isPortrait)
    }

    /**
     * Switch to a different pose detection model
     *
     * Use this to switch between fast (workout) and accurate (calibration) models
     *
     * @param modelPath Path to the model file (use Constants.POSE_MODEL_*)
     */
    @Synchronized
    override fun switchModel(modelPath: String) {
        if (modelPath == currentModelPath) {
            logger.d(TAG, "Already using model: $modelPath")
            return
        }

        logger.i(TAG, "Switching model: $currentModelPath → $modelPath")
        setupPoseLandmarker(modelPath)
    }

    /**
     * Get currently active model
     */
    override fun getCurrentModel(): String = currentModelPath

    @Synchronized
    override fun close() {
        try {
            isProcessing = false
            poseLandmarker?.close()
            poseLandmarker = null
            cachedResult.set(null)
            logger.d(TAG, "✅ PoseDetector closed")
        } catch (e: Exception) {
            logger.e(TAG, "Error closing PoseDetector")
        }
    }
}
