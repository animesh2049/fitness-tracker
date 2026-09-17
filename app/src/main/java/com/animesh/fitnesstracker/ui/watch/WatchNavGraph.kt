package com.animesh.fitnesstracker.ui.watch

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.animesh.fitnesstracker.ui.health.HealthRoutes

/** Registers the Watch screen at [HealthRoutes.WATCH]. Settings and the Health tab navigate here. */
fun NavGraphBuilder.watchGraph(navController: NavHostController) {
    composable(HealthRoutes.WATCH) {
        WatchScreen(onBack = { navController.popBackStack() })
    }
}
