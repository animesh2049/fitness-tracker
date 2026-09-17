package com.animesh.fitnesstracker.ui.progress

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.animesh.fitnesstracker.ui.navigation.navigateWorkoutSection

object ProgressRoutes {
    /** Workout section root; must stay "progress" (see WorkoutSection). */
    const val HUB = "progress"
    const val EXERCISE_HISTORY = "progress/exercise/{exerciseId}"
    fun exerciseHistory(id: Long) = "progress/exercise/$id"
}

/** Adds the Progress hub and the per-exercise history detail to the app's NavHost. */
fun NavGraphBuilder.progressGraph(navController: NavHostController) {
    composable(ProgressRoutes.HUB) {
        ProgressScreen(
            onOpenHistory = { navController.navigate(ProgressRoutes.exerciseHistory(it)) },
            onSection = { navController.navigateWorkoutSection(it) }
        )
    }
    composable(
        ProgressRoutes.EXERCISE_HISTORY,
        arguments = listOf(navArgument("exerciseId") { type = NavType.LongType })
    ) { entry ->
        val id = entry.arguments?.getLong("exerciseId") ?: return@composable
        ExerciseHistoryScreen(exerciseId = id, onBack = { navController.popBackStack() })
    }
}
