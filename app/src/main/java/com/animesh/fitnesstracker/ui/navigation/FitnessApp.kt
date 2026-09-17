package com.animesh.fitnesstracker.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.animesh.fitnesstracker.R
import com.animesh.fitnesstracker.ui.diet.DietRoutes
import com.animesh.fitnesstracker.ui.diet.dietGraph
import com.animesh.fitnesstracker.ui.health.healthGraph
import com.animesh.fitnesstracker.ui.history.historyGraph
import com.animesh.fitnesstracker.ui.plan.planGraph
import com.animesh.fitnesstracker.ui.progress.progressGraph
import com.animesh.fitnesstracker.ui.session.SessionScreen
import com.animesh.fitnesstracker.ui.settings.SettingsRoutes
import com.animesh.fitnesstracker.ui.settings.settingsGraph
import com.animesh.fitnesstracker.ui.theme.Tokens
import com.animesh.fitnesstracker.ui.today.TodayScreen
import com.animesh.fitnesstracker.ui.watch.watchGraph

/**
 * The three bottom tabs. Each is a hub: Workout opens on Today and carries the section row
 * (Today, History, Progress, Plan); Diet and Health have their own sub-screens.
 */
enum class TopLevel(val route: String, val labelRes: Int, val icon: ImageVector) {
    Workout(WorkoutSection.Today.route, R.string.nav_workout, Icons.Outlined.FitnessCenter),
    Diet("diet", R.string.nav_diet, Icons.Outlined.Restaurant),
    Health("health", R.string.nav_health, Icons.Outlined.MonitorHeart);

    /** True when [route] is inside this tab. */
    fun owns(route: String?): Boolean = when (this) {
        Workout -> WorkoutSection.owns(route)
        else -> route != null && (route == this.route || route.startsWith("${this.route}/"))
    }
}

object Routes {
    const val SESSION = "session/{sessionId}"
    fun session(id: Long) = "session/$id"
}

/** Routes that take over the whole screen (no bottom bar). */
private val fullScreenRoutes = setOf(Routes.SESSION, SettingsRoutes.SETTINGS)

/**
 * @param startRoute a route to open once the graph is ready, from a notification tap: "diet" or
 * "diet/meal/<id>". Null keeps the normal start destination. [onStartRouteConsumed] is called
 * after navigating so the same route can be requested again later.
 */
@Composable
fun FitnessApp(startRoute: String? = null, onStartRouteConsumed: () -> Unit = {}) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val showBar = currentDestination?.route !in fullScreenRoutes

    LaunchedEffect(startRoute) {
        if (startRoute == null) return@LaunchedEffect
        navController.openDeepRoute(startRoute)
        onStartRouteConsumed()
    }

    Scaffold(
        containerColor = Tokens.Ground,
        bottomBar = { if (showBar) BottomBar(navController, currentDestination?.route) }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevel.Workout.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(WorkoutSection.Today.route) {
                TodayScreen(
                    onOpenSession = { navController.navigate(Routes.session(it)) },
                    onOpenPlan = { navController.navigateWorkoutSection(WorkoutSection.Plan) },
                    onOpenSettings = { navController.navigate(SettingsRoutes.SETTINGS) },
                    onSection = { navController.navigateWorkoutSection(it) }
                )
            }
            composable(
                Routes.SESSION,
                arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("sessionId") ?: return@composable
                SessionScreen(sessionId = id, onClose = { navController.popBackStack() })
            }
            dietGraph(navController)
            healthGraph(navController)
            historyGraph(navController)
            progressGraph(navController)
            planGraph(navController)
            settingsGraph(navController)
            watchGraph(navController)
        }
    }
}

fun NavHostController.navigateTop(top: TopLevel) {
    navigate(top.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private const val MEAL_PREFIX = "diet/meal/"

/** Opens a route requested from outside the UI (notification tap). Unknown routes are ignored. */
private fun NavHostController.openDeepRoute(route: String) {
    when {
        route == DietRoutes.HUB -> navigateTop(TopLevel.Diet)
        route.startsWith(MEAL_PREFIX) -> {
            val id = route.removePrefix(MEAL_PREFIX).toLongOrNull() ?: return
            navigateTop(TopLevel.Diet)
            navigate(DietRoutes.meal(id))
        }
    }
}

@Composable
private fun BottomBar(navController: NavHostController, currentRoute: String?) {
    NavigationBar(containerColor = Tokens.Ground, tonalElevation = 0.dp) {
        TopLevel.entries.forEach { top ->
            val selected = top.owns(currentRoute)
            NavigationBarItem(
                selected = selected,
                // Tapping the tab you are already in returns to its top screen.
                onClick = { if (selected) navController.popBackStack(top.route, inclusive = false) else navController.navigateTop(top) },
                icon = { Icon(top.icon, contentDescription = null) },
                label = { Text(stringResource(top.labelRes), style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Tokens.Accent, selectedTextColor = Tokens.Accent,
                    unselectedIconColor = Tokens.Dim, unselectedTextColor = Tokens.Dim,
                    indicatorColor = Tokens.Ground
                )
            )
        }
    }
}

@Composable
fun Placeholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Tokens.Text)
    }
}
