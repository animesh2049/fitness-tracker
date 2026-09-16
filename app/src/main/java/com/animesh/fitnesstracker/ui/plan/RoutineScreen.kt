package com.animesh.fitnesstracker.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.BottomActionBar
import com.animesh.fitnesstracker.ui.components.EmptyState
import com.animesh.fitnesstracker.ui.components.GhostButton
import com.animesh.fitnesstracker.ui.components.PrimaryButton
import com.animesh.fitnesstracker.ui.components.ScreenHeader
import com.animesh.fitnesstracker.ui.components.SecondaryButton
import com.animesh.fitnesstracker.ui.components.SectionLabel
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.Tokens

/** Plan hub: the active routine's cycle, the week ahead, and links to the editors. */
@Composable
fun RoutineScreen(
    onEditRoutine: (Long) -> Unit,
    onAllRoutines: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenExercises: () -> Unit
) {
    val container = appContainer()
    val vm: RoutineViewModel = viewModel { RoutineViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()

    val allRoutines: @Composable () -> Unit = {
        TextButton(onClick = onAllRoutines, modifier = Modifier.heightIn(min = 44.dp)) {
            Text("All routines", style = MaterialTheme.typography.labelLarge, color = Tokens.Accent)
        }
    }

    Column(Modifier.fillMaxSize()) {
        val routine = state.routine
        when {
            state.loading -> Spacer(Modifier.weight(1f))
            routine == null -> {
                ScreenHeader("Plan · Routine", "No routine", "Nothing is active yet", trailing = allRoutines)
                EmptyState(
                    "No active routine",
                    "Build a cycle of workout groups and rest days. The cycle advances when you finish or skip a day.",
                    modifier = Modifier.weight(1f)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Create routine", { onEditRoutine(0L) })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("Groups", onOpenGroups, Modifier.weight(1f))
                            GhostButton("Exercises", onOpenExercises, Modifier.weight(1f))
                        }
                    }
                }
            }
            else -> {
                ScreenHeader("Plan · Routine", routine.routine.name, state.subtitle, trailing = allRoutines)
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { SectionLabel("Cycle") }
                    if (state.slots.isEmpty()) {
                        item {
                            Text("This routine has no slots yet. Edit it to add groups and rest days.", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                    items(state.slots, key = { it.index }) { row -> SlotCard(row) { vm.pickSlot(row) } }
                    item {
                        Text("Tap a slot to do it today instead.", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = Tokens.Dim, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                    item { SectionLabel("Coming up") }
                    item { UpcomingGrid(state.upcoming) }
                    if (state.log.isNotEmpty()) {
                        item { Spacer(Modifier.height(8.dp)) }
                        item {
                            AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    state.log.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.TextSoft) }
                                }
                            }
                        }
                    }
                }
                BottomActionBar {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Skip today", vm::skipToday, Modifier.weight(1f), height = 48)
                        SecondaryButton("Rest today", vm::restToday, Modifier.weight(1f), height = 48)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Edit routine", { onEditRoutine(routine.routine.id) }, Modifier.weight(1.4f), height = 48)
                        GhostButton("Groups", onOpenGroups, Modifier.weight(1f), height = 48)
                        GhostButton("Exercises", onOpenExercises, Modifier.weight(1f), height = 48)
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotCard(row: SlotRow, onClick: () -> Unit) {
    AppCard(
        borderColor = if (row.isToday) Tokens.Accent else Tokens.Border,
        padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = onClick
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(if (row.isToday) Tokens.Accent else Tokens.Surface2),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${row.index + 1}", style = MonoNumber,
                    color = when { row.isToday -> Tokens.AccentInk; row.isRest -> Tokens.Dim; else -> Tokens.Text }
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleLarge, color = if (row.isRest) Tokens.TextSoft else Tokens.Text)
                Text(row.meta, style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
            }
            val badge = when { row.isToday -> "Today"; row.isNext -> "Next"; else -> null }
            if (badge != null) {
                Text(badge.uppercase(), style = MaterialTheme.typography.labelMedium, color = if (row.isToday) Tokens.Accent else Tokens.Dim)
            }
        }
    }
}

@Composable
private fun UpcomingGrid(days: List<UpcomingDay>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { d ->
            val shape = RoundedCornerShape(8.dp)
            val (bg, border) = when {
                d.isToday -> Tokens.Surface to Tokens.Accent
                d.isRest -> Color.Transparent to Tokens.Surface2
                else -> Tokens.Surface to Tokens.Border
            }
            Column(
                Modifier.weight(1f).heightIn(min = 52.dp).clip(shape).background(bg).border(1.dp, border, shape).padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(d.day.uppercase(), style = MaterialTheme.typography.labelSmall, color = Tokens.Muted, maxLines = 1)
                Text(
                    d.name, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
                    color = if (d.isRest) Tokens.Dim else Tokens.Text, textAlign = TextAlign.Center, maxLines = 2
                )
            }
        }
    }
}
