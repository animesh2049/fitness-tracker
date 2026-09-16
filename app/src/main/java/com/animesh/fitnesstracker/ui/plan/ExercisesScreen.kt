package com.animesh.fitnesstracker.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.data.model.Muscles
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.BottomActionBar
import com.animesh.fitnesstracker.ui.components.OutlineChipButton
import com.animesh.fitnesstracker.ui.components.PillTag
import com.animesh.fitnesstracker.ui.components.PrimaryButton
import com.animesh.fitnesstracker.ui.components.ScreenHeader
import com.animesh.fitnesstracker.ui.components.SecondaryButton
import com.animesh.fitnesstracker.ui.theme.Tokens

@Composable
fun ExercisesScreen(onOpenExercise: (Long) -> Unit, onBack: () -> Unit) {
    val container = appContainer()
    val vm: ExercisesViewModel = viewModel { ExercisesViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Plan · Exercises", "Exercise library", "${plural(state.total, "exercise")} · tap one to edit")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanTextField(state.search, vm::setSearch, "Search", placeholder = "Bench, squat, plank…")
            MuscleFilterRow(state.muscle, vm::toggleMuscle)
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Show archived", style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.showArchived, onCheckedChange = vm::setShowArchived,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Tokens.AccentInk, checkedTrackColor = Tokens.Accent,
                        uncheckedThumbColor = Tokens.Muted, uncheckedTrackColor = Tokens.Surface2, uncheckedBorderColor = Tokens.BorderStrong
                    )
                )
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!state.loading && state.rows.isEmpty()) {
                item {
                    Text(
                        if (state.search.isBlank() && state.muscle == null) "No exercises yet." else "Nothing matches that search.",
                        style = MaterialTheme.typography.bodyLarge, color = Tokens.Muted, modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(state.rows, key = { it.id }) { row ->
                val archived = row.exercise.archived
                AppCard(onClick = { onOpenExercise(row.id) }, borderColor = if (archived) Tokens.Surface2 else Tokens.Border) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(row.exercise.name, style = MaterialTheme.typography.titleLarge, color = if (archived) Tokens.Muted else Tokens.Text)
                            Text(row.musclesLine, style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
                        }
                        if (archived) {
                            OutlineChipButton("Unarchive", { vm.unarchive(row.id) }, contentColor = Tokens.Accent, borderColor = Tokens.AccentBorder)
                        } else {
                            PillTag(row.typeLabel)
                        }
                    }
                }
            }
        }
        BottomActionBar {
            PrimaryButton("New exercise", { onOpenExercise(0L) })
            SecondaryButton("Back", onBack, Modifier.fillMaxWidth(), height = 44)
        }
    }
}

/** Horizontal scrolling row of muscle filter chips; one may be selected. */
@Composable
fun MuscleFilterRow(selected: String?, onToggle: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Muscles.ALL, key = { it }) { m -> ToggleChip(m, m == selected, { onToggle(m) }) }
    }
}
