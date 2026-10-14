package com.liftr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.liftr.domain.repository.CalibrationRepository
import com.liftr.domain.repository.SettingsRepository
import com.liftr.ui.navigation.NavGraph
import com.liftr.ui.theme.LiftrTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var calibrationRepository: CalibrationRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be installed before super.onCreate so the system swaps the
        // launch (splash) theme for the post-splash app theme correctly.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            LiftrTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavGraph(
                        calibrationRepository = calibrationRepository,
                        settingsRepository = settingsRepository
                    )
                }
            }
        }
    }
}
