package com.animesh.fitnesstracker.ui.plan

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.animesh.fitnesstracker.ui.navigation.navigateWorkoutSection

object PlanRoutes {
    const val HUB = "plan"
    const val GROUPS = "plan/groups"
    const val GROUP = "plan/groups/{groupId}"
    fun group(id: Long) = "plan/groups/$id"
    const val EXERCISES = "plan/exercises"
    const val EXERCISE = "plan/exercises/{exerciseId}"
    fun exercise(id: Long) = "plan/exercises/$id"
    const val ROUTINES = "plan/routines"
    const val ROUTINE_EDIT = "plan/routines/{routineId}"
    fun routineEdit(id: Long) = "plan/routines/$id"
}

/** Registers the Plan tab: routine hub, routines, groups and exercise library. Id 0 means "new". */
fun NavGraphBuilder.planGraph(navController: NavHostController) {
    composable(PlanRoutes.HUB) {
        RoutineScreen(
            onEditRoutine = { navController.navigate(PlanRoutes.routineEdit(it)) },
            onAllRoutines = { navController.navigate(PlanRoutes.ROUTINES) },
            onOpenGroups = { navController.navigate(PlanRoutes.GROUPS) },
            onOpenExercises = { navController.navigate(PlanRoutes.EXERCISES) },
            onSection = { navController.navigateWorkoutSection(it) }
        )
    }
    composable(PlanRoutes.ROUTINES) {
        RoutinesScreen(
            onEditRoutine = { navController.navigate(PlanRoutes.routineEdit(it)) },
            onBack = { navController.popBackStack() }
        )
    }
    composable(
        PlanRoutes.ROUTINE_EDIT,
        arguments = listOf(navArgument("routineId") { type = NavType.LongType })
    ) { entry ->
        val id = entry.arguments?.getLong("routineId") ?: 0L
        RoutineEditorScreen(routineId = id, onClose = { navController.popBackStack() })
    }
    composable(PlanRoutes.GROUPS) {
        GroupsScreen(
            onOpenGroup = { navController.navigate(PlanRoutes.group(it)) },
            onBack = { navController.popBackStack() }
        )
    }
    composable(
        PlanRoutes.GROUP,
        arguments = listOf(navArgument("groupId") { type = NavType.LongType })
    ) { entry ->
        val id = entry.arguments?.getLong("groupId") ?: return@composable
        GroupEditorScreen(groupId = id, onClose = { navController.popBackStack() })
    }
    composable(PlanRoutes.EXERCISES) {
        ExercisesScreen(
            onOpenExercise = { navController.navigate(PlanRoutes.exercise(it)) },
            onBack = { navController.popBackStack() }
        )
    }
    composable(
        PlanRoutes.EXERCISE,
        arguments = listOf(navArgument("exerciseId") { type = NavType.LongType })
    ) { entry ->
        val id = entry.arguments?.getLong("exerciseId") ?: 0L
        ExerciseEditorScreen(exerciseId = id, onClose = { navController.popBackStack() })
    }
}
