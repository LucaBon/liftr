package com.liftr.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.liftr.domain.repository.CalibrationRepository
import com.liftr.domain.repository.SettingsKeys
import com.liftr.domain.repository.SettingsRepository
import com.liftr.ui.screens.calibration.AnthropometricCalibrationScreen
import com.liftr.ui.screens.calibration.CalibrationScreen
import com.liftr.ui.screens.home.HomeScreen
import com.liftr.ui.screens.home.HomeViewModel
import com.liftr.ui.screens.onboarding.OnboardingScreen
import com.liftr.ui.screens.workout.WorkoutScreen
import com.liftr.ui.screens.settings.SettingsScreen
import com.liftr.ui.screens.analytics.AnalyticsScreen
import com.liftr.ui.screens.history.HistoryScreen

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    calibrationRepository: CalibrationRepository,
    settingsRepository: SettingsRepository
) {
    // Both flags must resolve before the graph is built. They come from
    // independent stores with independent first-emission timing, so gating on
    // only one of them lets NavHost capture the other's placeholder: a
    // calibrated returning user whose onboarding flag arrives first would be
    // sent to Calibration, and the later emission cannot move an already-created
    // NavHost off its start destination.
    val calibrated: Boolean? by produceState<Boolean?>(initialValue = null) {
        calibrationRepository.isCalibrated().collect { value = it }
    }
    val onboardingCompleted: Boolean? by produceState<Boolean?>(initialValue = null) {
        settingsRepository
            .getBooleanSetting(SettingsKeys.ONBOARDING_COMPLETED, defaultValue = false)
            .collect { value = it }
    }

    val resolvedOnboarding = onboardingCompleted
    val resolvedCalibrated = calibrated
    if (resolvedOnboarding == null || resolvedCalibrated == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startDestination = remember(resolvedOnboarding, resolvedCalibrated) {
        when {
            !resolvedOnboarding -> Screen.Onboarding.route
            resolvedCalibrated -> Screen.Home.route
            else -> Screen.Calibration.route
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { NavTransitions.enter() },
        exitTransition = { NavTransitions.exit() },
        popEnterTransition = { NavTransitions.popEnter() },
        popExitTransition = { NavTransitions.popExit() }
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onFinish = {
                    val replayFlow = navController.previousBackStackEntry
                        ?.destination?.route == Screen.Settings.route
                    if (replayFlow) {
                        navController.popBackStack()
                    } else {
                        val nextRoute = if (resolvedCalibrated) Screen.Home.route else Screen.Calibration.route
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
            com.liftr.ui.screens.anthropometric.AnthropometricDetailsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onStartWorkout = { navController.navigate(Screen.Workout.route) },
                onViewHistory = { navController.navigate(Screen.History.route) },
                onViewAnalytics = { navController.navigate(Screen.Analytics.route) },
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

/**
 * Shared screen-transition spec. Forward navigation slides the new screen in
 * from the right while the previous one parallax-slides out and fades; back
 * navigation mirrors it. Keeps motion directional and consistent so users
 * retain a sense of place in the navigation hierarchy.
 */
private object NavTransitions {
    private const val DURATION = 300
    private const val PARALLAX = 4 // outgoing screen moves at 1/4 the distance
    private val easing = FastOutSlowInEasing

    fun enter() =
        slideInHorizontally(tween(DURATION, easing = easing)) { it } +
            fadeIn(tween(DURATION, easing = easing))

    fun exit() =
        slideOutHorizontally(tween(DURATION, easing = easing)) { -it / PARALLAX } +
            fadeOut(tween(DURATION, easing = easing))

    fun popEnter() =
        slideInHorizontally(tween(DURATION, easing = easing)) { -it / PARALLAX } +
            fadeIn(tween(DURATION, easing = easing))

    fun popExit() =
        slideOutHorizontally(tween(DURATION, easing = easing)) { it } +
            fadeOut(tween(DURATION, easing = easing))
}
