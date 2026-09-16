package com.animesh.fitnesstracker.ui.diet

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.data.model.DietSettings
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.domain.diet.MealClock
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.SectionLabel
import com.animesh.fitnesstracker.ui.theme.MonoNumberLarge
import com.animesh.fitnesstracker.ui.theme.Tokens

/** Which time a picker dialog is editing. */
private sealed interface TimeTarget {
    data class WindowStart(val slot: MealSlot) : TimeTarget
    data class WindowEnd(val slot: MealSlot) : TimeTarget
    data object Prep : TimeTarget
}

@Composable
fun DietRemindersScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm: DietRemindersViewModel = viewModel { DietRemindersViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    var picker by remember { mutableStateOf<TimeTarget?>(null) }

    val prepLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.setPrepEnabled(true) }
    val mealLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.setMealEnabled(true) }
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val togglePrep: (Boolean) -> Unit = { on -> if (on && needsPermission) prepLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.setPrepEnabled(on) }
    val toggleMeal: (Boolean) -> Unit = { on -> if (on && needsPermission) mealLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.setMealEnabled(on) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Tokens.TextSoft)
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f).padding(start = 4.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("DIET · REMINDERS", style = MaterialTheme.typography.labelMedium, color = Tokens.Muted)
                Text("Meal times and reminders", style = MaterialTheme.typography.headlineLarge, color = Tokens.Text)
                Text("Times decide which meal the Diet tab shows as current.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
            }
        }

        if (state.loading) {
            Spacer(Modifier.weight(1f))
        } else {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SectionLabel("Meal windows")
                AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    Column {
                        MealSlot.entries.forEachIndexed { i, slot ->
                            WindowRow(slot, s, onStart = { picker = TimeTarget.WindowStart(slot) }, onEnd = { picker = TimeTarget.WindowEnd(slot) })
                            if (i < MealSlot.entries.size - 1) Divider()
                        }
                    }
                }
                state.problems.forEach { problem ->
                    Text(problem, style = MaterialTheme.typography.bodySmall, color = Tokens.Danger, modifier = Modifier.padding(horizontal = 4.dp))
                }
                Note("Between windows the tab shows the next meal and when it starts.")

                Gap()
                SectionLabel("Notifications")
                AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Prep for tomorrow", style = MaterialTheme.typography.bodyLarge, color = Tokens.Text)
                                Text("Only on days when a planned meal needs it", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Muted)
                            }
                            TimeChip(MealClock.formatMinute(s.prepReminderMinute)) { picker = TimeTarget.Prep }
                            ReminderSwitch(s.prepReminderEnabled, togglePrep)
                        }
                        Divider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("At each meal window", style = MaterialTheme.typography.bodyLarge, color = Tokens.Text)
                                Text("What to eat, when the window opens", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Muted)
                            }
                            ReminderSwitch(s.mealReminderEnabled, toggleMeal)
                        }
                    }
                }

                Gap()
                SectionLabel("How they look")
                if (s.prepReminderEnabled) PreviewCard(state.prepPreview)
                if (s.mealReminderEnabled) PreviewCard(state.windowPreview)
                if (!s.prepReminderEnabled && !s.mealReminderEnabled) {
                    Text(
                        "Reminders are off. The Diet tab still shows the current meal.",
                        style = MaterialTheme.typography.bodyMedium, color = Tokens.Dim, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp)
                    )
                }
            }
        }
    }

    picker?.let { target ->
        val (title, initial) = when (target) {
            is TimeTarget.WindowStart -> "${slotLabel(target.slot)} starts" to s.window(target.slot).first
            is TimeTarget.WindowEnd -> "${slotLabel(target.slot)} ends" to s.window(target.slot).last
            TimeTarget.Prep -> "Prep reminder" to s.prepReminderMinute
        }
        TimePickerDialog(
            title = title,
            initialMinute = initial,
            onConfirm = { minute ->
                when (target) {
                    is TimeTarget.WindowStart -> vm.setWindowStart(target.slot, minute)
                    is TimeTarget.WindowEnd -> vm.setWindowEnd(target.slot, minute)
                    TimeTarget.Prep -> vm.setPrepMinute(minute)
                }
                picker = null
            },
            onDismiss = { picker = null }
        )
    }
}

@Composable
private fun WindowRow(slot: MealSlot, s: DietSettings, onStart: () -> Unit, onEnd: () -> Unit) {
    val window = s.window(slot)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(slotLabel(slot), style = MaterialTheme.typography.bodyLarge, color = Tokens.Text, modifier = Modifier.weight(1f))
        TimeChip(MealClock.formatMinute(window.first), onStart)
        Text("to", style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted, modifier = Modifier.padding(horizontal = 2.dp))
        TimeChip(MealClock.formatMinute(window.last), onEnd)
    }
}

/** A tappable time with a 44 dp hit target. */
@Composable
private fun TimeChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MonoNumberLarge, color = Tokens.Text)
    }
}

@Composable
private fun ReminderSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Tokens.AccentInk, checkedTrackColor = Tokens.Accent, checkedBorderColor = Tokens.Accent,
            uncheckedThumbColor = Tokens.Muted, uncheckedTrackColor = Tokens.Surface2, uncheckedBorderColor = Tokens.BorderStrong
        )
    )
}

@Composable
private fun PreviewCard(preview: ReminderPreview) {
    AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(Tokens.Accent))
                Text(preview.header, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Muted)
            }
            Text(preview.title, style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
            Text(preview.body, style = MaterialTheme.typography.bodyMedium, color = Tokens.TextSoft)
            if (preview.actions.isNotEmpty()) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    preview.actions.forEach { Text(it, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = Tokens.Accent) }
                }
            }
        }
    }
}

/** Material3 has no TimePickerDialog, so this wraps [TimePicker] in an AlertDialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(title: String, initialMinute: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberTimePickerState(initialHour = initialMinute / 60, initialMinute = initialMinute % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Surface,
        titleContentColor = Tokens.Text,
        textContentColor = Tokens.Muted,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            TimePicker(
                state = pickerState,
                colors = TimePickerDefaults.colors(
                    clockDialColor = Tokens.Surface2,
                    clockDialSelectedContentColor = Tokens.AccentInk,
                    clockDialUnselectedContentColor = Tokens.Text,
                    selectorColor = Tokens.Accent,
                    containerColor = Tokens.Surface,
                    timeSelectorSelectedContainerColor = Tokens.AccentSurface,
                    timeSelectorUnselectedContainerColor = Tokens.Surface2,
                    timeSelectorSelectedContentColor = Tokens.Accent,
                    timeSelectorUnselectedContentColor = Tokens.Text
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour * 60 + pickerState.minute) }) {
                Text("Set", color = Tokens.Accent, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Tokens.TextSoft, style = MaterialTheme.typography.labelLarge) }
        }
    )
}

@Composable
private fun Gap() = Spacer(Modifier.height(6.dp))

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Surface2))

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Dim, modifier = Modifier.padding(horizontal = 4.dp))

