package com.animesh.fitnesstracker.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.animesh.fitnesstracker.data.model.ExerciseType
import com.animesh.fitnesstracker.data.model.GroupWithExercises
import com.animesh.fitnesstracker.data.model.ProgressionRule
import com.animesh.fitnesstracker.ui.theme.Tokens

/** Short label used on type pills. */
fun typeLabel(type: ExerciseType): String = when (type) {
    ExerciseType.WEIGHT -> "Weight"
    ExerciseType.BODYWEIGHT -> "Body"
    ExerciseType.TIMED -> "Timed"
}

fun ruleLabel(rule: ProgressionRule): String = when (rule) {
    ProgressionRule.LINEAR_WEIGHT -> "Linear weight"
    ProgressionRule.DOUBLE_PROGRESSION -> "Double progression"
    ProgressionRule.LINEAR_REPS -> "Linear reps"
    ProgressionRule.LINEAR_TIME -> "Linear time"
    ProgressionRule.NONE -> "None"
}

/** "5 exercises · 15 sets · chest, shoulders" */
fun groupMeta(g: GroupWithExercises): String {
    val n = g.exercises.size
    val parts = mutableListOf("$n ${if (n == 1) "exercise" else "exercises"}", "${g.workingSetCount} sets")
    if (g.muscles.isNotEmpty()) parts += g.muscles.joinToString(", ")
    return parts.joinToString(" · ")
}

fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

@Composable
fun planFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Tokens.Text,
    unfocusedTextColor = Tokens.Text,
    focusedContainerColor = Tokens.Surface,
    unfocusedContainerColor = Tokens.Surface,
    focusedBorderColor = Tokens.Accent,
    unfocusedBorderColor = Tokens.BorderStrong,
    errorBorderColor = Tokens.Danger,
    cursorColor = Tokens.Accent,
    focusedLabelColor = Tokens.Muted,
    unfocusedLabelColor = Tokens.Dim,
    focusedPlaceholderColor = Tokens.Dim,
    unfocusedPlaceholderColor = Tokens.Dim,
    focusedSupportingTextColor = Tokens.Muted,
    unfocusedSupportingTextColor = Tokens.Dim,
    errorSupportingTextColor = Tokens.Danger
)

@Composable
fun PlanTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    numeric: Boolean = false,
    decimal: Boolean = false,
    supporting: String? = null,
    isError: Boolean = false,
    minLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = when {
            decimal -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
            numeric -> KeyboardOptions(keyboardType = KeyboardType.Number)
            else -> KeyboardOptions.Default
        },
        shape = RoundedCornerShape(10.dp),
        colors = planFieldColors(),
        textStyle = MaterialTheme.typography.bodyLarge
    )
}

/** Toggle chip, 44 dp tall so it is comfortable to hit. */
@Composable
fun ToggleChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(if (selected) Tokens.Accent else Color.Transparent)
            .border(1.dp, if (selected) Tokens.Accent else Tokens.BorderStrong, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) Tokens.AccentInk else Tokens.TextSoft
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MuscleChips(all: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        all.forEach { m -> ToggleChip(m, m in selected, { onToggle(m) }) }
    }
}

/** Small icon button that opens a menu of text actions. */
@Composable
fun OverflowMenu(actions: List<Pair<String, () -> Unit>>, destructiveLast: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "More", tint = Tokens.Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Tokens.Surface2) {
            actions.forEachIndexed { i, (label, action) ->
                val last = destructiveLast && i == actions.lastIndex
                DropdownMenuItem(
                    text = { Text(label, color = if (last) Tokens.Danger else Tokens.Text, style = MaterialTheme.typography.bodyLarge) },
                    onClick = { open = false; action() }
                )
            }
        }
    }
}

/** Dialog asking for a single name. */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    label: String = "Name"
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Surface,
        titleContentColor = Tokens.Text,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { PlanTextField(text, { text = it }, label) },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) {
                Text(confirmText, color = Tokens.Accent, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Tokens.TextSoft, style = MaterialTheme.typography.labelLarge) }
        }
    )
}

/** A form row label above its control. */
@Composable
fun FieldLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Tokens.Muted, modifier = Modifier.padding(start = 4.dp))
}

/** Two-line row used in list cards: title on the left, trailing content on the right. */
@Composable
fun TitleRow(title: String, subtitle: String?, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Tokens.Text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
        }
        trailing()
    }
}
