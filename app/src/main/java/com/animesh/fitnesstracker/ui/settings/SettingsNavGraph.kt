package com.animesh.fitnesstracker.ui.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.animesh.fitnesstracker.ui.health.HealthRoutes

object SettingsRoutes {
    const val SETTINGS = "settings"
}

fun NavGraphBuilder.settingsGraph(navController: NavHostController) {
    composable(SettingsRoutes.SETTINGS) {
        SettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenWatch = { navController.navigate(HealthRoutes.WATCH) }
        )
    }
}
