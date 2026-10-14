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
        val detector = withContext(Dispatchers.Default) {
            VideoFramePoseDetector(context, logger, lensDistortionCorrector, Constants.POSE_MODEL_WORKOUT)
        }
        logger.d(TAG, "Preview sampling started")
        try {
            while (true) {
                delay(INTERVAL_MS)
                val bitmap = previewView.bitmap ?: continue
                val pose = withContext(Dispatchers.Default) {
                    detector.detectPose(bitmap, SystemClock.uptimeMillis())
                }
                pose?.let(onPose)
            }
        } finally {
            detector.close()
            logger.d(TAG, "Preview sampling stopped")
        }
    }

    companion object {
        private const val TAG = "PreviewPoseSampler"
        private const val INTERVAL_MS = 500L
    }
}
