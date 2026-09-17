package com.animesh.fitnesstracker.ui.history

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.animesh.fitnesstracker.ui.navigation.navigateWorkoutSection

object HistoryRoutes {
    const val HUB = "history"
    const val SESSION = "history/session/{sessionId}"
    fun session(id: Long) = "history/session/$id"
}

fun NavGraphBuilder.historyGraph(navController: NavHostController) {
    composable(HistoryRoutes.HUB) {
        HistoryScreen(
            onOpenSession = { navController.navigate(HistoryRoutes.session(it)) },
            onSection = { navController.navigateWorkoutSection(it) }
        )
    }
    composable(
        HistoryRoutes.SESSION,
        arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
    ) { entry ->
        val id = entry.arguments?.getLong("sessionId") ?: return@composable
        SessionDetailScreen(sessionId = id, onBack = { navController.popBackStack() })
    }
}
