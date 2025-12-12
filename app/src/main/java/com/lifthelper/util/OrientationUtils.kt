package com.lifthelper.util

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
 * Get the rotation degrees needed for pose overlay based on orientation
 * - Portrait mode: 90 degrees (camera sensor is rotated 90 degrees)
 * - Landscape mode: 0 degrees (no rotation needed)
 */
fun OrientationMode.getRotationDegrees(): Int {
    return when (this) {
        OrientationMode.PORTRAIT -> 90
        OrientationMode.LANDSCAPE -> 0
    }
}

/**
 * Check if the device is in portrait mode
 */
fun OrientationMode.isPortrait(): Boolean = this == OrientationMode.PORTRAIT

/**
 * Check if the device is in landscape mode
 */
fun OrientationMode.isLandscape(): Boolean = this == OrientationMode.LANDSCAPE
