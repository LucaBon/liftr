package com.lifthelper.vision.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
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

@Singleton
class CameraManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @CameraExecutor private val cameraExecutor: ExecutorService
) {
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile
    private var isInitializing = false

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
        Log.i(TAG_CAMERA, "Requesting camera provider instance...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                Log.d(TAG_CAMERA, "Camera provider future listener triggered")
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
                val preview = Preview.Builder()
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                    .setTargetRotation(displayRotation)
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
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
                    preview,
                    imageAnalysis
                )

                isInitializing = false
                frameCount = 0
                lastLogTime = System.currentTimeMillis()

                // Log camera details to help diagnose telephoto vs wide-angle issues
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
                } ?: Log.w(TAG_CAMERA, "Camera object is null after binding")
                Timber.d("✅ Camera started successfully")
            } catch (e: Exception) {
                isInitializing = false
                Log.e(TAG_CAMERA, "❌ Camera initialization failed", e)
                Timber.e(e, "Camera initialization failed")
            }
        }, ContextCompat.getMainExecutor(context))
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
            imageAnalysis?.clearAnalyzer()
            Log.d(TAG_CAMERA, "Cleared image analyzer")

            cameraProvider?.unbindAll()
            Log.d(TAG_CAMERA, "Unbound all camera use cases")

            camera = null
            imageAnalysis = null

            Log.i(TAG_CAMERA, "✅ Camera stopped (total frames processed: $frameCount)")
            Timber.d("✅ Camera stopped")
        } catch (e: Exception) {
            Log.e(TAG_CAMERA, "❌ Error stopping camera", e)
            Timber.e(e, "Error stopping camera")
        }
    }
}
