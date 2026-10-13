package com.liftr.util

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Represents the device orientation state
 */
enum class OrientationMode {
    PORTRAIT,
    LANDSCAPE
}

/**
 * Get the current orientation mode
 */
@Composable
fun rememberOrientation(): OrientationMode {
    val configuration = LocalConfiguration.current
    return when (configuration.orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> OrientationMode.LANDSCAPE
        else -> OrientationMode.PORTRAIT
    }
}

/**
 * Rotation degrees to apply when mapping pose landmarks to overlay canvas.
 *
 * Returns 0 because [com.liftr.vision.pose.PoseDetector] already rotates
 * the camera bitmap to upright orientation before running MediaPipe inference,
 * so the returned landmarks are already in upright (post-rotation) space and
 * match the PreviewView's upright rendering. Applying another rotation here
 * would double-rotate the skeleton.
 */
fun OrientationMode.getRotationDegrees(): Int = 0

/**
 * Check if the device is in portrait mode
 */
fun OrientationMode.isPortrait(): Boolean = this == OrientationMode.PORTRAIT

/**
 * Check if the device is in landscape mode
 */
fun OrientationMode.isLandscape(): Boolean = this == OrientationMode.LANDSCAPE
