package com.lifthelper

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.lifthelper.domain.repository.CalibrationRepository
import com.lifthelper.ui.navigation.NavGraph
import com.lifthelper.ui.theme.LiftHelperTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var calibrationRepository: CalibrationRepository

    private var orientationEventListener: OrientationEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Restrict to standard orientations only (no inverted portrait/landscape)
        setupOrientationListener()

        setContent {
            LiftHelperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavGraph(calibrationRepository = calibrationRepository)
                }
            }
        }
    }

    private fun setupOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                // Map sensor orientation to screen orientation
                val detectedOrientation = when (orientation) {
                    in 45..134 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE  // 270° - Left landscape
                    in 135..224 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT  // 180° - Upside down
                    in 225..314 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE         // 90° - Right landscape
                    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT                 // 0° - Normal portrait
                }

                // ONLY allow standard portrait (0°) and landscape (90°)
                // Block reverse portrait (180°) and reverse landscape (270°)
                val allowedOrientation = when (detectedOrientation) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ->
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ->
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

                    // Block inverted orientations - keep current orientation
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> {
                        // Don't change orientation - user is holding device in unsupported position
                        return
                    }

                    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }

                // Apply orientation change if different from current
                if (requestedOrientation != allowedOrientation) {
                    requestedOrientation = allowedOrientation
                }
            }
        }

        if (orientationEventListener?.canDetectOrientation() == true) {
            orientationEventListener?.enable()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationEventListener?.disable()
    }
}
