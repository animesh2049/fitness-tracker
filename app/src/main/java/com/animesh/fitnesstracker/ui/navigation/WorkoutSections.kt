package com.animesh.fitnesstracker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.animesh.fitnesstracker.ui.components.SegmentedRow

/**
 * The four sections of the Workout tab. Each is a sibling route under the tab: switching sections
 * never stacks, and Back from any section returns to Today.
 */
enum class WorkoutSection(val route: String, val label: String) {
    Today("today", "Today"),
    History("history", "History"),
    Progress("progress", "Progress"),
    Plan("plan", "Plan");

    companion object {
        /** True when [route] belongs to the Workout tab (a section root or anything nested under it). */
        fun owns(route: String?): Boolean =
            route != null && (entries.any { route == it.route || route.startsWith(it.route + "/") } || route.startsWith("session/"))
    }
}

/** The segmented section row shown under the header of every Workout section root. */
@Composable
fun WorkoutSectionRow(current: WorkoutSection, onSelect: (WorkoutSection) -> Unit) {
    val all = WorkoutSection.entries
    SegmentedRow(options = all.map { it.label }, selected = all.indexOf(current), onSelect = { onSelect(all[it]) })
}

/** Moves between Workout sections as siblings: the back stack is Today plus at most one section. */
fun NavHostController.navigateWorkoutSection(section: WorkoutSection) {
    if (currentBackStackEntry?.destination?.route == section.route) return
    navigate(section.route) {
        popUpTo(WorkoutSection.Today.route) { inclusive = false; saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
