package com.lifthelper.ui.navigation

sealed class Screen(val route: String) {
    object Calibration : Screen("calibration")
    object Home : Screen("home")
    object Workout : Screen("workout")
    object Analysis : Screen("analysis/{setId}") {
        fun createRoute(setId: Long) = "analysis/$setId"
    }
    object History : Screen("history")
    object Settings : Screen("settings")
}
