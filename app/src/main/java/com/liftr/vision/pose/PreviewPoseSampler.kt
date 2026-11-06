package com.liftr.vision.pose

import android.content.Context
import android.os.SystemClock
import androidx.camera.view.PreviewView
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.PoseResult
import com.liftr.util.Constants
import com.liftr.vision.camera.LensDistortionCorrector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Detects the pose in snapshots of the camera preview a couple of times a
 * second, for setup checks while no live pose stream is bound: a high-speed
 * recording session has no ImageAnalysis use case.
 */
class PreviewPoseSampler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
    private val lensDistortionCorrector: LensDistortionCorrector
) {

    /** Samples [previewView] until cancelled, passing every detected pose to [onPose]. */
    suspend fun sample(previewView: PreviewView, onPose: (PoseResult) -> Unit) {
        // The camera rebinds as the screen opens, which cancels and restarts
        // sampling within moments: create no detector for a session that short.
        delay(INTERVAL_MS)
        // CPU: two snapshots a second need no GPU, and a GPU landmarker closed
        // while its GL thread is still starting crashes the app natively.
        // Non-cancellable so a detector created as sampling stops is still closed below.
        val detector = withContext(NonCancellable + Dispatchers.Default) {
            VideoFramePoseDetector(context, logger, lensDistortionCorrector, Constants.POSE_MODEL_WORKOUT, useGpu = false)
        }
        logger.d(TAG, "Preview sampling started")
        try {
            while (true) {
                delay(INTERVAL_MS)
                val bitmap = previewView.bitmap ?: continue
                val pose = withContext(Dispatchers.Default) {
                    detector.detectPose(bitmap, SystemClock.uptimeMillis() * 1_000)
                }
                pose?.let(onPose)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Default) { detector.close() }
            logger.d(TAG, "Preview sampling stopped")
        }
    }

    companion object {
        private const val TAG = "PreviewPoseSampler"
        private const val INTERVAL_MS = 500L
    }
}
