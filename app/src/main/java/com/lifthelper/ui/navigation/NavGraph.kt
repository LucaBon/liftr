package com.lifthelper.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lifthelper.domain.repository.CalibrationRepository
import com.lifthelper.domain.repository.SettingsKeys
import com.lifthelper.domain.repository.SettingsRepository
import com.lifthelper.ui.screens.calibration.AnthropometricCalibrationScreen
import com.lifthelper.ui.screens.calibration.CalibrationScreen
import com.lifthelper.ui.screens.home.HomeScreen
import com.lifthelper.ui.screens.home.HomeViewModel
import com.lifthelper.ui.screens.onboarding.OnboardingScreen
import com.lifthelper.ui.screens.workout.WorkoutScreen
import com.lifthelper.ui.screens.settings.SettingsScreen
import com.lifthelper.ui.screens.analytics.AnalyticsScreen
import com.lifthelper.ui.screens.history.HistoryScreen

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    calibrationRepository: CalibrationRepository,
    settingsRepository: SettingsRepository
) {
    val isCalibrated by calibrationRepository.isCalibrated().collectAsState(initial = false)
    val onboardingCompleted: Boolean? by produceState<Boolean?>(initialValue = null) {
        settingsRepository
            .getBooleanSetting(SettingsKeys.ONBOARDING_COMPLETED, defaultValue = false)
            .collect { value = it }
    }

    // Gate NavHost creation until the onboarding flag resolves, otherwise
    // NavHost captures a stale startDestination and the first-launch or
    // returning user lands on the wrong screen.
    val resolvedOnboarding = onboardingCompleted ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startDestination = when {
        !resolvedOnboarding -> Screen.Onboarding.route
        isCalibrated -> Screen.Home.route
        else -> Screen.Calibration.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onFinish = {
                    val replayFlow = navController.previousBackStackEntry
                        ?.destination?.route == Screen.Settings.route
                    if (replayFlow) {
                        navController.popBackStack()
                    } else {
                        val nextRoute = if (isCalibrated) Screen.Home.route else Screen.Calibration.route
                        navController.navigate(nextRoute) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(Screen.Calibration.route) {
            CalibrationScreen(
                onCalibrationComplete = {
                    // Check if Settings is in the back stack (recalibration flow)
                    val settingsInBackStack = navController.previousBackStackEntry?.destination?.route == Screen.Settings.route

                    if (settingsInBackStack) {
                        // Coming from Settings - go back to Settings
                        navController.popBackStack()
                    } else {
                        // Initial calibration - go to Home
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Calibration.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(Screen.AnthropometricCalibration.route) {
            AnthropometricCalibrationScreen(
                onComplete = {
                    navController.popBackStack()
                },
                onSkip = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.AnthropometricDetails.route) {
            com.lifthelper.ui.screens.anthropometric.AnthropometricDetailsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onStartWorkout = { navController.navigate(Screen.Workout.route) },
                onViewHistory = { navController.navigate(Screen.Analytics.route) },
                onSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(Screen.Workout.route) {
            WorkoutScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.History.route) {
            HistoryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onRecalibrate = {
                    navController.navigate(Screen.Calibration.route) {
                        // Don't remove Settings from back stack
                        launchSingleTop = true
                    }
                },
                onAnthropometricCalibration = {
                    navController.navigate(Screen.AnthropometricCalibration.route) {
                        launchSingleTop = true
                    }
                },
                onAnthropometricDetails = {
                    navController.navigate(Screen.AnthropometricDetails.route) {
                        launchSingleTop = true
                    }
                },
                onReplayTutorial = {
                    navController.navigate(Screen.Onboarding.route) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.Analytics.route) {
            AnalyticsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
