package com.animesh.workouttracker.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.animesh.workouttracker.R
import com.animesh.workouttracker.ui.theme.Tokens

enum class TopLevel(val route: String, val labelRes: Int, val icon: ImageVector) {
    Today("today", R.string.nav_today, Icons.Outlined.EventAvailable),
    History("history", R.string.nav_history, Icons.Outlined.History),
    Progress("progress", R.string.nav_progress, Icons.Outlined.TrendingUp),
    Plan("plan", R.string.nav_plan, Icons.Outlined.FormatListBulleted)
}

@Composable
fun WorkoutApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination

    Scaffold(
        containerColor = Tokens.Ground,
        bottomBar = {
            NavigationBar(containerColor = Tokens.Ground, tonalElevation = 0.dp) {
                TopLevel.entries.forEach { top ->
                    val selected = currentDestination?.hierarchy?.any { it.route == top.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(top.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(top.icon, contentDescription = null) },
                        label = { Text(stringResource(top.labelRes), style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Tokens.Accent,
                            selectedTextColor = Tokens.Accent,
                            unselectedIconColor = Tokens.Dim,
                            unselectedTextColor = Tokens.Dim,
                            indicatorColor = Tokens.Ground
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevel.Today.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(TopLevel.Today.route) { Placeholder("Today") }
            composable(TopLevel.History.route) { Placeholder("History") }
            composable(TopLevel.Progress.route) { Placeholder("Progress") }
            composable(TopLevel.Plan.route) { Placeholder("Plan") }
        }
    }
}

@Composable
private fun Placeholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Tokens.Text)
    }
}
