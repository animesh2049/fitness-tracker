package com.animesh.workouttracker.ui.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

object SettingsRoutes {
    const val SETTINGS = "settings"
}

fun NavGraphBuilder.settingsGraph(navController: NavHostController) {
    composable(SettingsRoutes.SETTINGS) {
        SettingsScreen(onBack = { navController.popBackStack() })
    }
}
