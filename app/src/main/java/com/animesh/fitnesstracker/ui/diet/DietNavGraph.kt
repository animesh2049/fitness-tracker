package com.animesh.fitnesstracker.ui.diet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

object DietRoutes {
    const val HUB = "diet"
    const val MEALS = "diet/meals"
    const val MEAL = "diet/meal/{mealId}"
    fun meal(id: Long) = "diet/meal/$id"
    /** Meal id 0 means "new meal". */
    const val MEAL_EDIT = "diet/meal/{mealId}/edit"
    fun mealEdit(id: Long) = "diet/meal/$id/edit"
    const val WEEK = "diet/week"
    const val REMINDERS = "diet/reminders"
}

/** Registers the Diet tab: today's meals, the meal library, meal detail and editor, the week plan and reminder settings. */
fun NavGraphBuilder.dietGraph(navController: NavHostController) {
    composable(DietRoutes.HUB) {
        DietTodayScreen(
            onOpenMeal = { navController.navigate(DietRoutes.meal(it)) },
            onOpenWeek = { navController.navigate(DietRoutes.WEEK) },
            onOpenMeals = { navController.navigate(DietRoutes.MEALS) },
            onOpenReminders = { navController.navigate(DietRoutes.REMINDERS) }
        )
    }
    composable(DietRoutes.MEALS) {
        MealsScreen(
            onOpenMeal = { navController.navigate(DietRoutes.meal(it)) },
            onEditMeal = { navController.navigate(DietRoutes.mealEdit(it)) },
            onBack = { navController.popBackStack() }
        )
    }
    composable(DietRoutes.MEAL, arguments = listOf(navArgument("mealId") { type = NavType.LongType })) { entry ->
        val id = entry.arguments?.getLong("mealId") ?: return@composable
        MealScreen(
            mealId = id,
            onEdit = { navController.navigate(DietRoutes.mealEdit(it)) },
            onBack = { navController.popBackStack() },
            onAddToWeek = { navController.navigate(DietRoutes.WEEK) }
        )
    }
    composable(DietRoutes.MEAL_EDIT, arguments = listOf(navArgument("mealId") { type = NavType.LongType })) { entry ->
        val id = entry.arguments?.getLong("mealId") ?: 0L
        MealEditorScreen(mealId = id, onClose = { navController.popBackStack() })
    }
    composable(DietRoutes.WEEK) {
        WeekPlanScreen(
            onOpenMeals = { navController.navigate(DietRoutes.MEALS) },
            onOpenReminders = { navController.navigate(DietRoutes.REMINDERS) },
            onOpenMeal = { navController.navigate(DietRoutes.meal(it)) }
        )
    }
    composable(DietRoutes.REMINDERS) {
        DietRemindersScreen(onBack = { navController.popBackStack() })
    }
}

@Composable
private fun Placeholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(title) }
}
