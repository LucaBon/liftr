package com.lifthelper.vision.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as SystemCameraManager
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.util.Log
import com.lifthelper.di.CameraExecutor
import com.lifthelper.util.Constants
import com.lifthelper.util.Constants.TAG_CAMERA
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The set of CameraX use cases bound at once. CameraX supports at most 3
 * simultaneous use cases.
 */
enum class CameraMode {
    /** Preview + ImageAnalysis for real-time ML (default, calibration). */
    ANALYSIS_ONLY,

    /** Post-exercise mode: Preview + VideoCapture (no ML during recording). */
    RECORDING,

    /** Post-exercise mode with optional lite rep counter:
     *  Preview + VideoCapture + ImageAnalysis at low res/fps. */
    RECORDING_WITH_COUNTER
}

@Singleton
class CameraManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @CameraExecutor private val cameraExecutor: ExecutorService,
    private val lensDistortionCorrector: LensDistortionCorrector
) {
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentMode: CameraMode = CameraMode.ANALYSIS_ONLY

    @Volatile
    private var isInitializing = false

    /**
     * Incremented on every start and every stop. The `ProcessCameraProvider`
     * future resolves asynchronously, so a `stopCamera()` issued while a start
     * is still pending would otherwise be undone by the queued listener binding
     * the use cases anyway — leaving the camera live after the screen is gone.
     * The listener captures the generation it was scheduled with and aborts if
     * it no longer matches.
     *
     * Only touched from the main thread (both start paths and [stopCamera] are
     * main-thread entry points, and the listeners run on the main executor).
     */
    private var bindGeneration = 0

    private var frameCount = 0
    private var lastLogTime = System.currentTimeMillis()
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentPreviewView: PreviewView? = null
    private var currentOnFrameAnalyzed: ((ImageProxy) -> Unit)? = null
    private var currentUseHighResolution: Boolean = false

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onFrameAnalyzed: (ImageProxy) -> Unit,
        useFrontCamera: Boolean = false,
        useHighResolution: Boolean = false,  // For calibration, use higher resolution
        preferWideAngle: Boolean = false  // Try to use ultra-wide camera if available
    ) {
        currentCameraSelector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            // For calibration, prefer ultra-wide camera if available (wider field of view)
            if (preferWideAngle) {
                try {
                    // Try to build a selector for ultra-wide camera
                    CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build()
                } catch (e: Exception) {
                    Log.w(TAG_CAMERA, "Could not select ultra-wide camera, using default back camera")
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
        }
        currentLifecycleOwner = lifecycleOwner
        currentPreviewView = previewView
        currentOnFrameAnalyzed = onFrameAnalyzed
        currentUseHighResolution = useHighResolution
        Log.d(TAG_CAMERA, "startCamera() called - camera=$camera, isInitializing=$isInitializing, highRes=$useHighResolution")

        // Prevent multiple camera bindings
        if (camera != null || isInitializing) {
            Log.w(TAG_CAMERA, "Camera already started or initializing, skipping")
            Timber.w("Camera already started or initializing, skipping")
            return
        }

        isInitializing = true
        val bindToken = ++bindGeneration
        Log.i(TAG_CAMERA, "Requesting camera provider instance...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                Log.d(TAG_CAMERA, "Camera provider future listener triggered")
                if (bindToken != bindGeneration) {
                    Log.w(TAG_CAMERA, "Camera was stopped while initializing, skipping bind")
                    Timber.w("Camera was stopped while initializing, skipping bind")
                    return@addListener
                }
                cameraProvider = cameraProviderFuture.get()
                Log.i(TAG_CAMERA, "Camera provider obtained successfully")

                // Preview uses standard resolution for smooth display
                // ImageAnalysis uses high resolution for better pose detection (if requested)
                val previewWidth = Constants.CAMERA_TARGET_WIDTH
                val previewHeight = Constants.CAMERA_TARGET_HEIGHT
                val analysisWidth = if (useHighResolution) Constants.CAMERA_CALIBRATION_WIDTH else Constants.CAMERA_TARGET_WIDTH
                val analysisHeight = if (useHighResolution) Constants.CAMERA_CALIBRATION_HEIGHT else Constants.CAMERA_TARGET_HEIGHT

                // Get display rotation for proper image orientation
                val displayRotation = (context as? android.app.Activity)?.windowManager?.defaultDisplay?.rotation
                    ?: previewView.display?.rotation
                    ?: android.view.Surface.ROTATION_0

                Log.d(TAG_CAMERA, "Display rotation: $displayRotation")

                // Preview use case - use 16:9 aspect ratio to match ImageAnalysis
                // This ensures both use the same crop and what user sees matches what's analyzed
                val previewUseCase = Preview.Builder()
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                    .setTargetRotation(displayRotation)
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                preview = previewUseCase
                Log.d(TAG_CAMERA, "Preview use case configured with 16:9 aspect ratio, rotation: $displayRotation")

                // ImageAnalysis use case - use 16:9 to match preview
                // This ensures the analysis sees the same frame as the user
                // IMPORTANT: Set target rotation so CameraX can provide properly oriented images
                imageAnalysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                    .setTargetRotation(displayRotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            frameCount++
                            val currentTime = System.currentTimeMillis()
                            // Log FPS and actual resolution every 30 frames
                            if (frameCount % 30 == 0) {
                                val elapsed = currentTime - lastLogTime
                                val fps = (30 * 1000f) / elapsed
                                Log.d(TAG_CAMERA, "Camera FPS: %.1f (frame #%d), actual resolution: ${imageProxy.width}x${imageProxy.height}".format(fps, frameCount))
                                lastLogTime = currentTime
                            }
                            onFrameAnalyzed(imageProxy)
                        }
                    }
                Log.d(TAG_CAMERA, "ImageAnalysis use case configured: ${analysisWidth}x${analysisHeight} (RGBA_8888)")

                // Use selected camera
                Log.d(TAG_CAMERA, "Using ${if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) "front" else "back"} camera")

                // Unbind all and bind new use cases
                cameraProvider?.unbindAll()
                Log.d(TAG_CAMERA, "Unbound all previous use cases")

                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    currentCameraSelector,
                    previewUseCase,
                    imageAnalysis
                )

                isInitializing = false
                frameCount = 0
                lastLogTime = System.currentTimeMillis()

                // Log camera details and retrieve intrinsics for lens distortion correction
                camera?.let { cam ->
                    val cameraInfo = cam.cameraInfo
                    Log.i(TAG_CAMERA, "✅ Camera started successfully")
                    Log.d(TAG_CAMERA, "   Camera ID: ${cameraInfo.cameraSelector}")
                    Log.d(TAG_CAMERA, "   Sensor rotation: ${cameraInfo.sensorRotationDegrees}")
                    try {
                        // Try to log zoom ratio if available (telephoto cameras typically have >1.0x zoom)
                        val zoomState = cameraInfo.zoomState.value
                        Log.d(TAG_CAMERA, "   Zoom ratio: ${zoomState?.zoomRatio}, min: ${zoomState?.minZoomRatio}, max: ${zoomState?.maxZoomRatio}")
                    } catch (e: Exception) {
                        Log.d(TAG_CAMERA, "   Could not read zoom state")
                    }

                    // Retrieve camera intrinsics for lens distortion correction
                    retrieveCameraIntrinsics(analysisWidth, analysisHeight)
                } ?: Log.w(TAG_CAMERA, "Camera object is null after binding")
                Timber.d("✅ Camera started successfully")
            } catch (e: Exception) {
                isInitializing = false
                Log.e(TAG_CAMERA, "❌ Camera initialization failed", e)
                Timber.e(e, "Camera initialization failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Updates the target rotation of the Preview and ImageAnalysis use cases.
     *
     * The Activity declares `configChanges="orientation|screenSize"`, so it is
     * not recreated on rotation and CameraX won't pick up the new display
     * rotation automatically. Call this from Compose whenever the device
     * orientation changes so that:
     *  - PreviewView renders the camera feed upright, and
     *  - `ImageProxy.imageInfo.rotationDegrees` matches the new upright frame
     *    (the PoseDetector rotates the bitmap using that value, so landmarks
     *    end up in the same coordinate space as the preview).
     *
     * @param displayRotation One of [android.view.Surface.ROTATION_0],
     *   [android.view.Surface.ROTATION_90], [android.view.Surface.ROTATION_180],
     *   [android.view.Surface.ROTATION_270].
     */
    fun updateTargetRotation(displayRotation: Int) {
        Log.d(TAG_CAMERA, "updateTargetRotation($displayRotation)")
        preview?.targetRotation = displayRotation
        imageAnalysis?.targetRotation = displayRotation
    }

    fun switchCamera() {
        Log.d(TAG_CAMERA, "switchCamera() called")
        val newCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Restart camera with new selector, preserving resolution setting
        stopCamera()
        currentLifecycleOwner?.let { lifecycleOwner ->
            currentPreviewView?.let { previewView ->
                currentOnFrameAnalyzed?.let { onFrameAnalyzed ->
                    startCamera(
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewView,
                        onFrameAnalyzed = onFrameAnalyzed,
                        useFrontCamera = newCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA,
                        useHighResolution = currentUseHighResolution
                    )
                }
            }
        }
    }

    /**
     * Check if currently using front camera
     */
    fun isFrontCamera(): Boolean {
        return currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
    }

    /**
     * Retrieve camera intrinsics for lens distortion correction.
     *
     * This method attempts to get device-specific camera calibration parameters
     * to correct for lens distortion (barrel/pincushion effects).
     */
    private fun retrieveCameraIntrinsics(imageWidth: Int, imageHeight: Int) {
        try {
            val systemCameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? SystemCameraManager
            if (systemCameraManager == null) {
                Log.w(TAG_CAMERA, "Could not get system camera manager for intrinsics")
                return
            }

            // Get the physical camera ID
            val cameraIdList = systemCameraManager.cameraIdList
            if (cameraIdList.isEmpty()) {
                Log.w(TAG_CAMERA, "No camera IDs available")
                return
            }

            // Find the camera ID matching our current selector (front/back)
            val isFrontCamera = currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
            val cameraId = cameraIdList.firstOrNull { id ->
                val characteristics = systemCameraManager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                lensFacing == if (isFrontCamera) {
                    CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    CameraCharacteristics.LENS_FACING_BACK
                }
            }

            if (cameraId == null) {
                Log.w(TAG_CAMERA, "Could not find matching camera ID")
                return
            }

            Log.d(TAG_CAMERA, "Retrieving intrinsics for camera: $cameraId")

            // Clear previous intrinsics when camera changes
            lensDistortionCorrector.clearCache()

            // Retrieve intrinsics
            val intrinsics = lensDistortionCorrector.retrieveIntrinsics(
                systemCameraManager,
                cameraId,
                imageWidth,
                imageHeight
            )

            if (intrinsics != null) {
                Log.i(TAG_CAMERA, "✅ Camera intrinsics loaded for lens distortion correction")
            } else {
                Log.i(TAG_CAMERA, "ℹ️ Camera intrinsics not available, using empirical correction model")
            }
        } catch (e: Exception) {
            Log.w(TAG_CAMERA, "Failed to retrieve camera intrinsics: ${e.message}")
        }
    }

    /**
     * Start camera in recording mode for post-exercise analysis (Feature 17).
     *
     * Binds Preview + VideoCapture, plus an optional low-res ImageAnalysis when
     * [withRepCounter] is true (for an approximate live rep counter). The plain
     * [startCamera] continues to drive ANALYSIS_ONLY mode (real-time, calibration).
     *
     * @param lifecycleOwner Lifecycle to bind the camera to
     * @param previewView Preview surface
     * @param videoRecordingManager Manager that creates the VideoCapture use case
     * @param withRepCounter If true, also bind ImageAnalysis at 320x240 throttled to ~5fps
     * @param onFrameAnalyzed Frame callback (only used if [withRepCounter] is true)
     * @param useFrontCamera Whether to use the front camera
     */
    fun startCameraForRecording(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        videoRecordingManager: VideoRecordingManager,
        withRepCounter: Boolean = false,
        onFrameAnalyzed: ((ImageProxy) -> Unit)? = null,
        useFrontCamera: Boolean = false
    ) {
        currentMode = if (withRepCounter) CameraMode.RECORDING_WITH_COUNTER else CameraMode.RECORDING
        currentCameraSelector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        currentLifecycleOwner = lifecycleOwner
        currentPreviewView = previewView
        currentOnFrameAnalyzed = onFrameAnalyzed

        if (camera != null || isInitializing) {
            Log.w(TAG_CAMERA, "Camera already started or initializing, skipping (recording mode)")
            return
        }
        isInitializing = true
        val bindToken = ++bindGeneration

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                if (bindToken != bindGeneration) {
                    Log.w(TAG_CAMERA, "Camera was stopped while initializing, skipping bind (recording mode)")
                    return@addListener
                }
                cameraProvider = cameraProviderFuture.get()

                val displayRotation = (context as? android.app.Activity)?.windowManager?.defaultDisplay?.rotation
                    ?: previewView.display?.rotation
                    ?: android.view.Surface.ROTATION_0

                val previewUseCase = Preview.Builder()
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                    .setTargetRotation(displayRotation)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                preview = previewUseCase

                val newVideoCapture = videoRecordingManager.createVideoCaptureUseCase()
                videoCapture = newVideoCapture

                val useCases = mutableListOf<UseCase>(previewUseCase, newVideoCapture)

                if (withRepCounter && onFrameAnalyzed != null) {
                    val repCounterAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(320, 240))
                        .setTargetRotation(displayRotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also { analysis ->
                            var lastRepCounterFrame = 0L
                            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                val now = System.currentTimeMillis()
                                // Throttle to ~5 FPS (200ms interval) so video capture keeps priority.
                                if (now - lastRepCounterFrame >= 200L) {
                                    lastRepCounterFrame = now
                                    onFrameAnalyzed(imageProxy)
                                } else {
                                    imageProxy.close()
                                }
                            }
                        }
                    imageAnalysis = repCounterAnalysis
                    useCases.add(repCounterAnalysis)
                }

                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    currentCameraSelector,
                    *useCases.toTypedArray()
                )

                isInitializing = false
                frameCount = 0
                lastLogTime = System.currentTimeMillis()

                camera?.let {
                    Log.i(TAG_CAMERA, "✅ Camera started in ${currentMode.name} mode")
                    retrieveCameraIntrinsics(
                        if (withRepCounter) 320 else Constants.CAMERA_CALIBRATION_WIDTH,
                        if (withRepCounter) 240 else Constants.CAMERA_CALIBRATION_HEIGHT
                    )
                } ?: Log.w(TAG_CAMERA, "Camera object null after binding (recording mode)")
            } catch (e: Exception) {
                isInitializing = false
                Log.e(TAG_CAMERA, "❌ Camera initialization failed in recording mode", e)
                Timber.e(e, "Camera initialization failed in recording mode")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Get the VideoCapture use case for starting/stopping recordings. */
    fun getVideoCapture(): VideoCapture<Recorder>? = videoCapture

    /** True when a VideoCapture is bound and the camera can record video. */
    fun isReadyForRecording(): Boolean = videoCapture != null

    /**
     * Ensure the camera is bound in a recording mode (Preview + VideoCapture).
     *
     * Navigation/timing races can occasionally leave the shared camera bound in
     * ANALYSIS_ONLY mode, so [getVideoCapture] returns null and post-exercise
     * recording can't start. This self-heals by forcing a rebind into recording
     * mode using the cached lifecycle owner and preview view.
     *
     * Binding is asynchronous — callers should poll [isReadyForRecording] /
     * [getVideoCapture] after invoking this.
     *
     * @return true if a rebind was initiated (or already ready); false if it
     *   couldn't be attempted because no preview/lifecycle has been bound yet.
     */
    fun ensureRecordingBinding(
        videoRecordingManager: VideoRecordingManager,
        withRepCounter: Boolean
    ): Boolean {
        if (videoCapture != null) return true
        val owner = currentLifecycleOwner
        val view = currentPreviewView
        if (owner == null || view == null) {
            Log.w(TAG_CAMERA, "Cannot rebind for recording: no cached lifecycle/preview view")
            return false
        }
        Log.i(TAG_CAMERA, "VideoCapture missing — rebinding camera into recording mode (self-heal)")
        stopCamera()
        startCameraForRecording(
            lifecycleOwner = owner,
            previewView = view,
            videoRecordingManager = videoRecordingManager,
            withRepCounter = withRepCounter,
            onFrameAnalyzed = if (withRepCounter) currentOnFrameAnalyzed else null,
            useFrontCamera = currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
        )
        return true
    }

    fun stopCamera() {
        try {
            Log.d(TAG_CAMERA, "stopCamera() called - camera=$camera, isInitializing=$isInitializing")

            // Only stop if camera is actually running
            if (camera == null && !isInitializing) {
                Log.d(TAG_CAMERA, "Camera not running, skipping stop")
                Timber.d("Camera not running, skipping stop")
                return
            }

            isInitializing = false
            // Invalidate any provider-future listener still queued from a start
            // that hasn't resolved yet, so it can't bind after we tear down.
            bindGeneration++
            imageAnalysis?.clearAnalyzer()
            Log.d(TAG_CAMERA, "Cleared image analyzer")

            cameraProvider?.unbindAll()
            Log.d(TAG_CAMERA, "Unbound all camera use cases")

            camera = null
            imageAnalysis = null
            preview = null
            videoCapture = null
            currentMode = CameraMode.ANALYSIS_ONLY

            // Clear lens distortion intrinsics cache
            lensDistortionCorrector.clearCache()

            Log.i(TAG_CAMERA, "✅ Camera stopped (total frames processed: $frameCount)")
            Timber.d("✅ Camera stopped")
        } catch (e: Exception) {
            Log.e(TAG_CAMERA, "❌ Error stopping camera", e)
            Timber.e(e, "Error stopping camera")
        }
    }
}
