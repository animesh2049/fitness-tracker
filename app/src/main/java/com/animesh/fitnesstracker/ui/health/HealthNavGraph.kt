package com.animesh.fitnesstracker.ui.health

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.animesh.fitnesstracker.ui.history.HistoryRoutes

/**
 * Registers the Health tab: the day hub, the sleep night, trends, the activity list and the
 * activity detail. The Watch screen ([HealthRoutes.WATCH]) is registered elsewhere; this graph only
 * navigates to it.
 */
fun NavGraphBuilder.healthGraph(navController: NavHostController) {
    composable(HealthRoutes.HUB) {
        HealthTodayScreen(
            onOpenWatch = { navController.navigate(HealthRoutes.WATCH) },
            onOpenSleep = { navController.navigate(HealthRoutes.sleep(it)) },
            onOpenTrends = { navController.navigate(HealthRoutes.TRENDS) },
            onOpenActivities = { navController.navigate(HealthRoutes.ACTIVITIES) },
            onOpenActivity = { navController.navigate(HealthRoutes.activity(it)) }
        )
    }
    composable(HealthRoutes.SLEEP, arguments = listOf(navArgument("epochDay") { type = NavType.LongType })) { entry ->
        val day = entry.arguments?.getLong("epochDay") ?: return@composable
        SleepScreen(
            epochDay = day,
            onBack = { navController.popBackStack() },
            onOpenTrends = { navController.navigate(HealthRoutes.TRENDS) }
        )
    }
    composable(HealthRoutes.TRENDS) {
        TrendsScreen(onBack = { navController.popBackStack() })
    }
    composable(HealthRoutes.ACTIVITIES) {
        ActivitiesScreen(
            onBack = { navController.popBackStack() },
            onOpenActivity = { navController.navigate(HealthRoutes.activity(it)) }
        )
    }
    composable(HealthRoutes.ACTIVITY, arguments = listOf(navArgument("activityId") { type = NavType.LongType })) { entry ->
        val id = entry.arguments?.getLong("activityId") ?: return@composable
        ActivityDetailScreen(
            activityId = id,
            onBack = { navController.popBackStack() },
            onOpenSession = { navController.navigate(HistoryRoutes.session(it)) }
        )
    }
}
