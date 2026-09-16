package com.animesh.fitnesstracker.ui.plan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.data.model.ExerciseType
import com.animesh.fitnesstracker.data.model.Muscles
import com.animesh.fitnesstracker.data.model.ProgressionRule
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.BottomActionBar
import com.animesh.fitnesstracker.ui.components.ConfirmDialog
import com.animesh.fitnesstracker.ui.components.GhostButton
import com.animesh.fitnesstracker.ui.components.PrimaryButton
import com.animesh.fitnesstracker.ui.components.ScreenHeader
import com.animesh.fitnesstracker.ui.components.SecondaryButton
import com.animesh.fitnesstracker.ui.theme.Tokens

@Composable
fun ExerciseEditorScreen(exerciseId: Long, onClose: () -> Unit) {
    val container = appContainer()
    val vm: ExerciseEditorViewModel = viewModel(key = "exercise-$exerciseId") { ExerciseEditorViewModel(container, exerciseId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.closed.collect { onClose() } }
    val cancel: () -> Unit = { if (state.dirty) confirmDiscard = true else onClose() }
    BackHandler(onBack = cancel)

    val d = state.draft
    val unitName = state.unit.name.lowercase()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Plan · " + if (state.isNew) "New exercise" else "Edit exercise",
            d.name.ifBlank { if (state.isNew) "New exercise" else "Exercise" },
            when {
                state.archived -> "Archived · hidden from pickers"
                state.isNew -> "Saved to the library"
                else -> "${typeLabel(d.type)} · ${ruleLabel(d.rule)}"
            }
        )
        if (!state.loaded) {
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { PlanTextField(d.name, vm::setName, "Name", placeholder = "Bench press", isError = state.error != null, supporting = state.error) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel("Type")
                        SegmentedRow(
                            options = listOf(ExerciseType.WEIGHT to "Weight", ExerciseType.BODYWEIGHT to "Bodyweight", ExerciseType.TIMED to "Timed"),
                            selected = d.type, onSelect = vm::setType
                        )
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel("Muscles")
                        MuscleChips(Muscles.ALL, d.muscles, vm::toggleMuscle)
                    }
                }
                item {
                    PlanTextField(d.restText, vm::setRest, "Default rest between sets (s)", numeric = true, placeholder = "Blank uses the global ${state.globalRest} s")
                }
                if (d.type != ExerciseType.TIMED) {
                    item {
                        PlanTextField(d.incrementText, vm::setIncrement, "Weight increment ($unitName)", decimal = true, supporting = "How much progression adds at a time, for example 2.5 for dumbbells or 5 for a barbell.")
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel("Progression rule")
                        RuleDropdown(state.rules, d.rule, vm::setRule)
                        Text(ruleHelp(d.rule), style = MaterialTheme.typography.bodySmall, color = Tokens.Dim, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
                if (d.rule == ProgressionRule.DOUBLE_PROGRESSION) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PlanTextField(d.repMinText, vm::setRepMin, "Rep range min", Modifier.weight(1f), numeric = true)
                            PlanTextField(d.repMaxText, vm::setRepMax, "Rep range max", Modifier.weight(1f), numeric = true)
                        }
                    }
                }
                if (d.type == ExerciseType.TIMED) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PlanTextField(d.timeStepText, vm::setTimeStep, "Time step (s)", Modifier.weight(1f), numeric = true)
                            PlanTextField(d.timeMaxText, vm::setTimeMax, "Max seconds", Modifier.weight(1f), numeric = true, placeholder = "No cap")
                        }
                    }
                }
                item { PlanTextField(d.notes, vm::setNotes, "Notes", placeholder = "Use the cable machine near the window", singleLine = false, minLines = 2) }
                if (!state.isNew) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.archived) GhostButton("Unarchive", vm::unarchive, Modifier.weight(1f), contentColor = Tokens.Accent, borderColor = Tokens.AccentBorder)
                            GhostButton(
                                if (state.referenced) "Archive exercise" else "Delete exercise", { confirmDelete = true }, Modifier.weight(1f),
                                contentColor = Tokens.Danger, borderColor = Tokens.DangerBorder
                            )
                        }
                    }
                }
            }
        }
        BottomActionBar {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", cancel, Modifier.weight(1f), height = 52)
                PrimaryButton("Save exercise", vm::save, Modifier.weight(2f), height = 52, enabled = state.loaded)
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog("Discard changes?", "Your edits to this exercise will be lost.", "Discard", onConfirm = { confirmDiscard = false; vm.discard() }, onDismiss = { confirmDiscard = false }, destructive = true)
    }
    if (confirmDelete) {
        val name = d.name.ifBlank { "this exercise" }
        if (state.referenced) {
            ConfirmDialog(
                "Archive $name?",
                "It has logged history or is used in a group, so it will be archived instead of deleted. Archived exercises are hidden from pickers but stay visible in history. You can unarchive it later.",
                "Archive",
                onConfirm = { confirmDelete = false; vm.delete() }, onDismiss = { confirmDelete = false }, destructive = true
            )
        } else {
            ConfirmDialog(
                "Delete $name?",
                "It has no history and is not used in any group, so it will be deleted permanently.",
                "Delete",
                onConfirm = { confirmDelete = false; vm.delete() }, onDismiss = { confirmDelete = false }, destructive = true
            )
        }
    }
}

private fun ruleHelp(rule: ProgressionRule): String = when (rule) {
    ProgressionRule.LINEAR_WEIGHT -> "Hit every working set and the weight goes up by the increment next time."
    ProgressionRule.DOUBLE_PROGRESSION -> "Reps climb through the range first; at the top, weight goes up and reps drop back to the min."
    ProgressionRule.LINEAR_REPS -> "Hit every working set and the target reps go up by one next time."
    ProgressionRule.LINEAR_TIME -> "Hold the full time on every set and the target grows by the time step."
    ProgressionRule.NONE -> "No suggestions. Targets only change when you edit them."
}

@Composable
private fun <T> SegmentedRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(shape).background(Tokens.Surface).border(1.dp, Tokens.BorderStrong, shape),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier.weight(1f).fillMaxSize().padding(3.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (active) Tokens.Accent else Color.Transparent)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = if (active) Tokens.AccentInk else Tokens.TextSoft)
            }
        }
    }
}

@Composable
private fun RuleDropdown(options: List<ProgressionRule>, selected: ProgressionRule, onSelect: (ProgressionRule) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box {
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(shape).background(Tokens.Surface).border(1.dp, Tokens.BorderStrong, shape)
                .clickable { open = true }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(ruleLabel(selected), style = MaterialTheme.typography.bodyLarge, color = Tokens.Text, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, tint = Tokens.Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Tokens.Surface2) {
            options.forEach { r ->
                DropdownMenuItem(
                    text = { Text(ruleLabel(r), color = if (r == selected) Tokens.Accent else Tokens.Text, style = MaterialTheme.typography.bodyLarge) },
                    onClick = { open = false; onSelect(r) }
                )
            }
        }
    }
}
