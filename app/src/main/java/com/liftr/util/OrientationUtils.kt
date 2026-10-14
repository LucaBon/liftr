package com.liftr.util

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.liftr.domain.model.ExerciseType

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

/**
 * Screen orientation to record an exercise in. Standing lifts and jumps are
 * tall, so portrait fills the frame and gives the vertical axis more pixels;
 * a bench press lifter lies horizontally and only fits well in landscape.
 */
fun ExerciseType.preferredScreenOrientation(): Int =
    if (this == ExerciseType.BENCH_PRESS) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

fun Context.findActivity(): ComponentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    return null
}
