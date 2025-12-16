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
                // Only allow standard portrait and landscape (not inverted)
                val newOrientation = when (orientation) {
                    in 45..134 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE  // Landscape (left)
                    in 135..224 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT          // Portrait (inverted - block this)
                    in 225..314 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE         // Landscape (right)
                    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT                 // Portrait (normal)
                }

                // Only allow portrait and landscape (not reverse variants)
                when (newOrientation) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> {
                        if (requestedOrientation != newOrientation) {
                            requestedOrientation = newOrientation
                        }
                    }
                    else -> {
                        // Block inverted portrait - keep current orientation
                    }
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
