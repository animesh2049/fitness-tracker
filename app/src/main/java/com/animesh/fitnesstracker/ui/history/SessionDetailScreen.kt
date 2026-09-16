package com.animesh.fitnesstracker.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.data.model.ExerciseType
import com.animesh.fitnesstracker.data.model.SessionExerciseWithSets
import com.animesh.fitnesstracker.data.model.SessionSet
import com.animesh.fitnesstracker.data.model.WeightUnit
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.BottomActionBar
import com.animesh.fitnesstracker.ui.components.ConfirmDialog
import com.animesh.fitnesstracker.ui.components.EmptyState
import com.animesh.fitnesstracker.ui.components.GhostButton
import com.animesh.fitnesstracker.ui.components.PillTag
import com.animesh.fitnesstracker.ui.components.SectionLabel
import com.animesh.fitnesstracker.ui.components.Stepper
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.Tokens
import com.animesh.fitnesstracker.util.Weights

@Composable
fun SessionDetailScreen(sessionId: Long, onBack: () -> Unit) {
    val container = appContainer()
    val vm: SessionDetailViewModel = viewModel(key = "session-detail-$sessionId") { SessionDetailViewModel(container, sessionId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Pair<SessionSet, ExerciseType>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val session = state.session
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Tokens.TextSoft)
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f).padding(start = 4.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.dateLabel.uppercase(), style = MaterialTheme.typography.labelMedium, color = Tokens.Muted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(session?.session?.groupName ?: if (state.loaded) "Session" else "", style = MaterialTheme.typography.headlineMedium, color = Tokens.Text)
                    if (state.abandoned) PillTag("Abandoned", color = Tokens.Warning, borderColor = Tokens.WarningBorder)
                }
                if (state.meta.isNotEmpty()) Text(state.meta, style = MonoNumber.copy(fontSize = 13.sp), color = Tokens.Muted)
            }
        }

        when {
            !state.loaded -> Spacer(Modifier.weight(1f))
            session == null -> EmptyState("Session not found", "It may have been deleted.", Modifier.weight(1f))
            else -> {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(session.sortedExercises.size, key = { session.sortedExercises[it].exercise.id }) { i ->
                        ExerciseCard(session.sortedExercises[i], state.unit, onEditSet = { set, type -> editing = set to type })
                    }
                    item(key = "notes") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            SectionLabel("Notes")
                            NotesField(initial = session.session.notes, onChange = vm::saveNotes)
                        }
                    }
                }
                BottomActionBar {
                    GhostButton(
                        "Delete session", { confirmDelete = true }, Modifier.fillMaxWidth(),
                        contentColor = Tokens.Danger, borderColor = Tokens.DangerBorder
                    )
                }
            }
        }
    }

    editing?.let { (set, type) ->
        EditSetDialog(
            set = set, type = type, unit = state.unit,
            onSave = { vm.saveSet(it); editing = null },
            onDismiss = { editing = null }
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete session?",
            body = "Every set logged in this session is removed. This cannot be undone.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = { confirmDelete = false; vm.delete(onBack) },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun NotesField(initial: String, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
        placeholder = { Text("How did it go?", color = Tokens.Dim) },
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Tokens.Text, unfocusedTextColor = Tokens.Text,
            focusedContainerColor = Tokens.Surface, unfocusedContainerColor = Tokens.Surface,
            focusedBorderColor = Tokens.BorderStrong, unfocusedBorderColor = Tokens.Border,
            cursorColor = Tokens.Accent
        )
    )
}

@Composable
private fun ExerciseCard(se: SessionExerciseWithSets, unit: WeightUnit, onEditSet: (SessionSet, ExerciseType) -> Unit) {
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(se.exercise.exerciseName, style = MaterialTheme.typography.titleLarge, color = Tokens.Text, modifier = Modifier.weight(1f))
            if (se.exercise.skipped) PillTag("Skipped", color = Tokens.Muted)
            PillTag(
                when (se.exercise.exerciseType) {
                    ExerciseType.WEIGHT -> "Weight"; ExerciseType.BODYWEIGHT -> "Body"; ExerciseType.TIMED -> "Timed"
                }
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            se.sortedSets.forEach { set -> SetRow(set, se.exercise.exerciseType, unit, onClick = { onEditSet(set, se.exercise.exerciseType) }) }
        }
        if (se.exercise.notes.isNotBlank()) Text(se.exercise.notes, style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
    }
}

private fun targetText(set: SessionSet, type: ExerciseType, unit: WeightUnit): String = describe(set.targetReps, set.targetWeightKg, set.targetSeconds, type, unit)
private fun actualText(set: SessionSet, type: ExerciseType, unit: WeightUnit): String = describe(set.actualReps, set.actualWeightKg, set.actualSeconds, type, unit)

private fun describe(reps: Int?, kg: Double?, seconds: Int?, type: ExerciseType, unit: WeightUnit): String = when (type) {
    ExerciseType.TIMED -> "${seconds ?: 0} s"
    ExerciseType.WEIGHT -> "${reps ?: 0} × ${Weights.formatWithUnit(kg ?: 0.0, unit)}"
    ExerciseType.BODYWEIGHT -> if ((kg ?: 0.0) > 0) "${reps ?: 0} × +${Weights.formatWithUnit(kg!!, unit)}" else "${reps ?: 0}"
}

@Composable
private fun SetRow(set: SessionSet, type: ExerciseType, unit: WeightUnit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(44.dp)) {
            if (set.isWarmup) PillTag("W-up", color = Tokens.Dim)
            else Text("Set ${set.position + 1}", style = MaterialTheme.typography.labelSmall, color = Tokens.Muted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(actualText(set, type, unit), style = MonoNumber, color = if (set.completed) Tokens.Text else Tokens.Muted)
            Text("target ${targetText(set, type, unit)}", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = Tokens.Dim)
        }
        when {
            !set.completed -> PillTag("Not done", color = Tokens.Dim, borderColor = Tokens.Border)
            set.hitTarget -> PillTag("Hit", color = Tokens.Accent, borderColor = Tokens.AccentBorder)
            else -> PillTag("Missed", color = Tokens.Danger, borderColor = Tokens.DangerBorder)
        }
    }
}

@Composable
private fun EditSetDialog(set: SessionSet, type: ExerciseType, unit: WeightUnit, onSave: (SessionSet) -> Unit, onDismiss: () -> Unit) {
    var reps by remember { mutableStateOf(set.actualReps ?: set.targetReps ?: 0) }
    var weight by remember { mutableStateOf(Weights.toDisplay(set.actualWeightKg ?: set.targetWeightKg ?: 0.0, unit)) }
    var seconds by remember { mutableStateOf(set.actualSeconds ?: set.targetSeconds ?: 0) }
    var completed by remember { mutableStateOf(set.completed) }
    val weightStep = if (unit == WeightUnit.KG) 2.5 else 5.0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Surface,
        titleContentColor = Tokens.Text,
        textContentColor = Tokens.Muted,
        title = { Text(if (set.isWarmup) "Warm-up set ${set.position + 1}" else "Set ${set.position + 1}", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Target ${targetText(set, type, unit)}", style = MonoNumber, color = Tokens.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (type == ExerciseType.TIMED) {
                        Stepper("Seconds", seconds.toString(), onMinus = { seconds = (seconds - 5).coerceAtLeast(0) }, onPlus = { seconds += 5 }, modifier = Modifier.weight(1f))
                    } else {
                        Stepper("Reps", reps.toString(), onMinus = { reps = (reps - 1).coerceAtLeast(0) }, onPlus = { reps += 1 }, modifier = Modifier.weight(1f))
                        Stepper(
                            unit.name.lowercase(), Weights.format(weight),
                            onMinus = { weight = (weight - weightStep).coerceAtLeast(0.0) }, onPlus = { weight += weightStep },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable { completed = !completed },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = completed, onCheckedChange = { completed = it },
                        colors = CheckboxDefaults.colors(checkedColor = Tokens.Accent, checkmarkColor = Tokens.AccentInk, uncheckedColor = Tokens.Muted)
                    )
                    Text("Completed", style = MaterialTheme.typography.bodyLarge, color = Tokens.Text)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updated = set.copy(
                    actualReps = if (type == ExerciseType.TIMED) set.actualReps else reps,
                    actualWeightKg = if (type == ExerciseType.TIMED) set.actualWeightKg else Weights.toKg(weight, unit),
                    actualSeconds = if (type == ExerciseType.TIMED) seconds else set.actualSeconds,
                    completed = completed,
                    completedAt = if (completed) (set.completedAt ?: System.currentTimeMillis()) else null
                )
                onSave(updated)
            }) { Text("Save", color = Tokens.Accent, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Tokens.TextSoft, style = MaterialTheme.typography.labelLarge) }
        }
    )
}
