package com.animesh.fitnesstracker.ui.plan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.data.model.Exercise
import com.animesh.fitnesstracker.data.model.ExerciseType
import com.animesh.fitnesstracker.data.model.SetPrescription
import com.animesh.fitnesstracker.data.model.WeightUnit
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.BottomActionBar
import com.animesh.fitnesstracker.ui.components.ConfirmDialog
import com.animesh.fitnesstracker.ui.components.EmptyState
import com.animesh.fitnesstracker.ui.components.PillTag
import com.animesh.fitnesstracker.ui.components.PrimaryButton
import com.animesh.fitnesstracker.ui.components.SecondaryButton
import com.animesh.fitnesstracker.ui.components.Stepper
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.MonoNumberLarge
import com.animesh.fitnesstracker.ui.theme.Tokens
import com.animesh.fitnesstracker.util.Weights

@Composable
fun GroupEditorScreen(groupId: Long, onClose: () -> Unit) {
    val container = appContainer()
    val vm: GroupEditorViewModel = viewModel(key = "group-$groupId") { GroupEditorViewModel(container, groupId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var removeTarget by remember { mutableStateOf<ExerciseRow?>(null) }

    // Name and notes are written when the screen goes away as well as on Done.
    DisposableEffect(vm) { onDispose { vm.flushText() } }
    val done: () -> Unit = { vm.flushText(); onClose() }
    BackHandler(enabled = state.picker.open) { vm.closePicker() }

    if (state.picker.open) {
        ExercisePicker(state, vm)
        return
    }

    Column(Modifier.fillMaxSize()) {
        when {
            !state.loaded -> Spacer(Modifier.weight(1f))
            state.missing -> EmptyState("Group not found", "It may have been deleted.", Modifier.weight(1f)) { SecondaryButton("Back", onClose) }
            else -> {
                EditableHeader(
                    eyebrow = if (state.group?.group?.isTemplate == true) "Plan · Edit template" else "Plan · Edit group",
                    name = state.name, onName = vm::setName, meta = state.meta
                )
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item { PlanTextField(state.notes, vm::setNotes, "Notes", placeholder = "Anything to remember on this day", singleLine = false) }
                    itemsIndexed(state.rows, key = { _, r -> r.id }) { i, row ->
                        ExerciseEditorCard(
                            row = row, unit = state.unit,
                            canUp = i > 0, canDown = i < state.rows.lastIndex,
                            onUp = { vm.moveExercise(i, i - 1) }, onDown = { vm.moveExercise(i, i + 1) },
                            onRemove = { removeTarget = row }, vm = vm
                        )
                    }
                    item { AddExerciseButton(vm::openPicker) }
                }
                BottomActionBar {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("+ Add exercise", vm::openPicker, Modifier.weight(1f), height = 52)
                        PrimaryButton("Done", done, Modifier.weight(1f), height = 52)
                    }
                }
            }
        }
    }

    state.editing?.let { edit ->
        SetEditDialog(edit, state.unit, onSave = vm::saveSet, onDismiss = vm::cancelEdit)
    }
    removeTarget?.let { row ->
        ConfirmDialog(
            title = "Remove ${row.ge.exercise.name}?",
            body = "Its ${plural(row.sets.size, "set")} in this group are removed. The exercise stays in the library.",
            confirmText = "Remove",
            onConfirm = { vm.removeExercise(row); removeTarget = null },
            onDismiss = { removeTarget = null },
            destructive = true
        )
    }
}

@Composable
private fun EditableHeader(eyebrow: String, name: String, onName: (String) -> Unit, meta: String) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium, color = Tokens.Muted)
        BasicTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineLarge.copy(color = Tokens.Text),
            cursorBrush = SolidColor(Tokens.Accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (name.isEmpty()) Text("Group name", style = MaterialTheme.typography.headlineLarge, color = Tokens.Faint)
                    inner()
                }
            }
        )
        Text(meta, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
    }
}

@Composable
private fun ExerciseEditorCard(
    row: ExerciseRow, unit: WeightUnit,
    canUp: Boolean, canDown: Boolean,
    onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit,
    vm: GroupEditorViewModel
) {
    AppCard(padding = PaddingValues(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.DragIndicator, contentDescription = null, tint = Tokens.Faint, modifier = Modifier.size(20.dp))
                Text(row.ge.exercise.name, style = MaterialTheme.typography.titleLarge, color = Tokens.Text, modifier = Modifier.weight(1f).padding(start = 4.dp))
                PillTag(row.typeLabel)
                IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Move up", tint = if (canUp) Tokens.TextSoft else Tokens.Faint)
                }
                IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Move down", tint = if (canDown) Tokens.TextSoft else Tokens.Faint)
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove exercise", tint = Tokens.Dim)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                row.sets.forEachIndexed { j, s -> SetRowView(j + 1, s, onEdit = { vm.editSet(row, s.set) }, onWarm = { vm.toggleWarmup(s.set) }, onRemove = { vm.removeSet(s.set) }) }
                if (row.sets.isEmpty()) Text("No sets. Add one below.", style = MaterialTheme.typography.bodySmall, color = Tokens.Dim, modifier = Modifier.padding(vertical = 8.dp))
            }
            Row(Modifier.padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallOutlineButton("+ Add set", { vm.addSet(row) }, contentColor = Tokens.Accent, borderColor = Tokens.AccentBorder, bold = true)
                if (row.nextName != null) {
                    if (row.superset) SmallOutlineButton("Unlink superset", { vm.toggleSuperset(row) }, contentColor = Tokens.Warning, borderColor = Tokens.WarningBorder)
                    else SmallOutlineButton("Superset with next", { vm.toggleSuperset(row) }, contentColor = Tokens.Muted, borderColor = Tokens.BorderStrong)
                }
            }
            if (row.superset) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.NorthEast, contentDescription = null, tint = Tokens.Warning, modifier = Modifier.size(14.dp))
                    Text("Superset with ${row.nextName} · rest after both", style = MaterialTheme.typography.bodySmall, color = Tokens.Warning)
                }
            }
        }
    }
}

@Composable
private fun SetRowView(num: Int, s: SetRow, onEdit: () -> Unit, onWarm: () -> Unit, onRemove: () -> Unit) {
    val warm = s.set.isWarmup
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onEdit),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("$num", style = MonoNumber, color = Tokens.Dim, modifier = Modifier.width(20.dp))
        Text(s.text, style = MonoNumberLarge, color = if (warm) Tokens.Muted else Tokens.Text, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                .background(if (warm) Tokens.Warning else Color.Transparent)
                .border(1.dp, if (warm) Tokens.Warning else Tokens.BorderStrong, RoundedCornerShape(8.dp))
                .clickable(onClick = onWarm),
            contentAlignment = Alignment.Center
        ) {
            Text("W", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = if (warm) Tokens.WarningInk else Tokens.Dim)
        }
        Box(Modifier.size(width = 44.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Text("×", style = MaterialTheme.typography.titleLarge, color = Tokens.Dim)
        }
    }
}

@Composable
private fun SmallOutlineButton(text: String, onClick: () -> Unit, contentColor: Color, borderColor: Color, bold: Boolean = false) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = contentColor)
    ) {
        Text(text, style = if (bold) MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize) else MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun AddExerciseButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(shape).border(1.dp, Tokens.Faint, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("+ Add exercise", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = Tokens.Text)
    }
}

@Composable
private fun ExercisePicker(state: GroupEditorState, vm: GroupEditorViewModel) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PLAN · ADD EXERCISE", style = MaterialTheme.typography.labelMedium, color = Tokens.Muted)
            Text("Pick an exercise", style = MaterialTheme.typography.headlineLarge, color = Tokens.Text)
            Text("Added to ${state.name.ifBlank { "this group" }} with three default sets.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanTextField(state.picker.search, vm::setPickerSearch, "Search", placeholder = "Bench, squat, plank…")
            MuscleFilterRow(state.picker.muscle, vm::togglePickerMuscle)
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.pickerExercises.isEmpty()) {
                item { Text("Nothing matches. Create exercises under Plan · Exercises.", style = MaterialTheme.typography.bodyLarge, color = Tokens.Muted, modifier = Modifier.padding(16.dp)) }
            }
            items(state.pickerExercises, key = { it.id }) { e ->
                AppCard(onClick = { vm.pick(e) }) {
                    TitleRow(e.name, e.muscleList.joinToString(", ").ifEmpty { "No muscle tags" }) { PillTag(typeLabel(e.type)) }
                }
            }
        }
        BottomActionBar { SecondaryButton("Cancel", vm::closePicker, Modifier.fillMaxWidth(), height = 52) }
    }
}

/** Edits one set prescription. Weight is shown in the user's unit and stored in kg. */
@Composable
private fun SetEditDialog(edit: SetEdit, unit: WeightUnit, onSave: (SetPrescription) -> Unit, onDismiss: () -> Unit) {
    val set = edit.set
    val ex: Exercise = edit.exercise
    val timed = ex.type == ExerciseType.TIMED
    var reps by rememberSaveable(set.id) { mutableIntStateOf(set.targetReps ?: 0) }
    var seconds by rememberSaveable(set.id) { mutableIntStateOf(set.targetSeconds ?: 0) }
    var weightText by rememberSaveable(set.id) { mutableStateOf(set.targetWeightKg?.let { Weights.format(Weights.toDisplay(it, unit)) } ?: "") }
    var restText by rememberSaveable(set.id) { mutableStateOf(set.restSecondsOverride?.toString() ?: "") }
    val unitName = unit.name.lowercase()
    val incrementDisplay = Weights.toDisplay(ex.weightIncrementKg, unit).let { if (it <= 0) 1.0 else it }
    val weightDisplay = weightText.replace(',', '.').toDoubleOrNull()

    fun stepWeight(delta: Double) {
        val next = ((weightDisplay ?: 0.0) + delta).coerceAtLeast(0.0)
        weightText = Weights.format(Weights.roundTo(next, incrementDisplay))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Surface,
        titleContentColor = Tokens.Text,
        title = { Text("Set ${set.position + 1} · ${ex.name}", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (timed) {
                    Stepper("Seconds", "$seconds", onMinus = { seconds = (seconds - 5).coerceAtLeast(0) }, onPlus = { seconds += 5 }, modifier = Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stepper("Reps", "$reps", onMinus = { reps = (reps - 1).coerceAtLeast(0) }, onPlus = { reps += 1 }, modifier = Modifier.weight(1f))
                        Stepper(
                            unitName, weightDisplay?.let { Weights.format(it) } ?: "0",
                            onMinus = { stepWeight(-incrementDisplay) }, onPlus = { stepWeight(incrementDisplay) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    PlanTextField(
                        weightText, { weightText = it }, "Weight ($unitName)", decimal = true,
                        placeholder = if (ex.type == ExerciseType.BODYWEIGHT) "Added load, blank for none" else null
                    )
                }
                PlanTextField(restText, { restText = it.filter { ch -> ch.isDigit() } }, "Rest after this set (s)", numeric = true, placeholder = "Blank uses the exercise default")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val weightKg = weightDisplay?.let { Weights.toKg(it, unit) }?.takeIf { it > 0 || ex.type == ExerciseType.WEIGHT }
                onSave(
                    set.copy(
                        targetReps = if (timed) null else reps,
                        targetSeconds = if (timed) seconds else null,
                        targetWeightKg = if (timed) null else weightKg,
                        restSecondsOverride = restText.toIntOrNull()?.takeIf { it > 0 }
                    )
                )
            }) { Text("Save", color = Tokens.Accent, style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Tokens.TextSoft, style = MaterialTheme.typography.labelLarge) } }
    )
}
