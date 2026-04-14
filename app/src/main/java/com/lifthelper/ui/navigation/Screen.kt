package com.lifthelper.ui.navigation

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Calibration : Screen("calibration")
    object AnthropometricCalibration : Screen("anthropometric_calibration")
    object AnthropometricDetails : Screen("anthropometric_details")
    object Home : Screen("home")
    object Workout : Screen("workout")
    object Analysis : Screen("analysis/{setId}") {
        fun createRoute(setId: Long) = "analysis/$setId"
    }
    object History : Screen("history")
    object Analytics : Screen("analytics")
    object Settings : Screen("settings")
}
