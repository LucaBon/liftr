package com.lifthelper.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lifthelper.data.local.preferences.SettingsDataStore
import com.lifthelper.ui.screens.calibration.CalibrationScreen
import com.lifthelper.ui.screens.home.HomeScreen
import com.lifthelper.ui.screens.home.HomeViewModel
import com.lifthelper.ui.screens.workout.WorkoutScreen
import com.lifthelper.ui.screens.settings.SettingsScreen
import com.lifthelper.ui.screens.history.HistoryScreen

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    settingsDataStore: SettingsDataStore
) {
    val isCalibrated by settingsDataStore.isCalibrated.collectAsState(initial = false)

    val startDestination = if (isCalibrated) {
        Screen.Home.route
    } else {
        Screen.Calibration.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Calibration.route) {
            CalibrationScreen(
                onCalibrationComplete = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Calibration.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onStartWorkout = { navController.navigate(Screen.Workout.route) },
                onViewHistory = { navController.navigate(Screen.History.route) },
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
                }
            )
        }

        // TODO: Add Analysis screen
    }
}
