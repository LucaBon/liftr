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

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onFrameAnalyzed: (ImageProxy) -> Unit,
        useFrontCamera: Boolean = false
    ) {
        currentCameraSelector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        currentLifecycleOwner = lifecycleOwner
        currentPreviewView = previewView
        currentOnFrameAnalyzed = onFrameAnalyzed
        Log.d(TAG_CAMERA, "startCamera() called - camera=$camera, isInitializing=$isInitializing")

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

                // Preview use case
                val preview = Preview.Builder()
                    .setTargetResolution(Size(Constants.CAMERA_TARGET_WIDTH, Constants.CAMERA_TARGET_HEIGHT))
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                Log.d(TAG_CAMERA, "Preview use case configured: ${Constants.CAMERA_TARGET_WIDTH}x${Constants.CAMERA_TARGET_HEIGHT}")

                // ImageAnalysis use case
                imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(Constants.CAMERA_TARGET_WIDTH, Constants.CAMERA_TARGET_HEIGHT))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            frameCount++
                            val currentTime = System.currentTimeMillis()
                            // Log FPS every 30 frames
                            if (frameCount % 30 == 0) {
                                val elapsed = currentTime - lastLogTime
                                val fps = (30 * 1000f) / elapsed
                                Log.d(TAG_CAMERA, "Camera FPS: %.1f (frame #%d)".format(fps, frameCount))
                                lastLogTime = currentTime
                            }
                            onFrameAnalyzed(imageProxy)
                        }
                    }
                Log.d(TAG_CAMERA, "ImageAnalysis use case configured with RGBA_8888 format")

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

                Log.i(TAG_CAMERA, "✅ Camera started successfully - camera=$camera")
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

        // Restart camera with new selector
        stopCamera()
        currentLifecycleOwner?.let { lifecycleOwner ->
            currentPreviewView?.let { previewView ->
                currentOnFrameAnalyzed?.let { onFrameAnalyzed ->
                    startCamera(
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewView,
                        onFrameAnalyzed = onFrameAnalyzed,
                        useFrontCamera = newCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
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
