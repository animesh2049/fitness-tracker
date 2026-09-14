package com.animesh.workouttracker.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.workouttracker.backup.ImportMode
import com.animesh.workouttracker.data.model.WeightUnit
import com.animesh.workouttracker.ui.appContainer
import com.animesh.workouttracker.ui.components.AppCard
import com.animesh.workouttracker.ui.components.SecondaryButton
import com.animesh.workouttracker.ui.components.SectionLabel
import com.animesh.workouttracker.ui.theme.MonoNumberLarge
import com.animesh.workouttracker.ui.theme.Tokens
import com.animesh.workouttracker.util.Dates
import android.net.Uri

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    val today = Dates.date(Dates.todayEpochDay()).toString()
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportJson) }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(vm::exportCsv) }
    val openImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pendingImport = uri }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Tokens.TextSoft)
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f).padding(start = 4.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SETTINGS", style = MaterialTheme.typography.labelMedium, color = Tokens.Muted)
                Text("Settings", style = MaterialTheme.typography.headlineLarge, color = Tokens.Text)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionLabel("Units")
            AppCard {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(44.dp)) {
                    WeightUnit.entries.forEachIndexed { i, unit ->
                        SegmentedButton(
                            selected = s.unit == unit,
                            onClick = { vm.update { it.copy(unit = unit) } },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = WeightUnit.entries.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = Tokens.AccentSurface, activeContentColor = Tokens.Accent, activeBorderColor = Tokens.AccentBorder,
                                inactiveContainerColor = Color.Transparent, inactiveContentColor = Tokens.TextSoft, inactiveBorderColor = Tokens.BorderStrong
                            ),
                            icon = {}
                        ) { Text(unit.name.lowercase(), style = MaterialTheme.typography.titleSmall) }
                    }
                }
                Text("Weights are stored in kg and shown in the unit you pick.", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
            }

            Gap()
            SectionLabel("Timers")
            AppCard {
                CounterRow("Rest between sets", "${s.defaultRestSeconds} s",
                    onMinus = { vm.update { it.copy(defaultRestSeconds = (it.defaultRestSeconds - 5).coerceAtLeast(0)) } },
                    onPlus = { vm.update { it.copy(defaultRestSeconds = it.defaultRestSeconds + 5) } })
                CounterRow("Rest between exercises", "${s.defaultExerciseRestSeconds} s",
                    onMinus = { vm.update { it.copy(defaultExerciseRestSeconds = (it.defaultExerciseRestSeconds - 5).coerceAtLeast(0)) } },
                    onPlus = { vm.update { it.copy(defaultExerciseRestSeconds = it.defaultExerciseRestSeconds + 5) } })
                CounterRow("Get-ready countdown", "${s.countdownSeconds} s",
                    onMinus = { vm.update { it.copy(countdownSeconds = (it.countdownSeconds - 1).coerceAtLeast(0)) } },
                    onPlus = { vm.update { it.copy(countdownSeconds = it.countdownSeconds + 1) } })
                Divider()
                SwitchRow("Sound", s.soundEnabled) { on -> vm.update { it.copy(soundEnabled = on) } }
                SwitchRow("Vibration", s.vibrationEnabled) { on -> vm.update { it.copy(vibrationEnabled = on) } }
                SwitchRow("Keep screen awake in a session", s.keepScreenAwake) { on -> vm.update { it.copy(keepScreenAwake = on) } }
                SwitchRow("Auto-start rest after a set", s.autoStartRest) { on -> vm.update { it.copy(autoStartRest = on) } }
            }

            Gap()
            SectionLabel("Progression")
            AppCard {
                CounterRow("Deload after failed sessions", s.deloadAfterFailures.toString(),
                    onMinus = { vm.update { it.copy(deloadAfterFailures = (it.deloadAfterFailures - 1).coerceIn(1, 6)) } },
                    onPlus = { vm.update { it.copy(deloadAfterFailures = (it.deloadAfterFailures + 1).coerceIn(1, 6)) } })
                CounterRow("Deload by", "${s.deloadPercent} %",
                    onMinus = { vm.update { it.copy(deloadPercent = (it.deloadPercent - 5).coerceIn(5, 25)) } },
                    onPlus = { vm.update { it.copy(deloadPercent = (it.deloadPercent + 5).coerceIn(5, 25)) } })
                Text(
                    "After ${s.deloadAfterFailures} missed sessions in a row on an exercise, the weight drops by ${s.deloadPercent} %.",
                    style = MaterialTheme.typography.bodySmall, color = Tokens.Muted
                )
            }

            Gap()
            SectionLabel("Backup")
            AppCard {
                SecondaryButton("Export all data (JSON)", { exportJson.launch("workout-backup-$today.json") }, Modifier.fillMaxWidth(), enabled = !state.backupBusy)
                SecondaryButton("Export history (CSV)", { exportCsv.launch("workout-history-$today.csv") }, Modifier.fillMaxWidth(), enabled = !state.backupBusy)
                SecondaryButton("Import backup (JSON)", { openImport.launch(arrayOf("application/json")) }, Modifier.fillMaxWidth(), enabled = !state.backupBusy)
                val status = when {
                    state.backupBusy -> "Working…"
                    state.backupStatus != null -> state.backupStatus
                    else -> "Backups are plain files you choose where to save."
                }
                val failed = state.backupStatus?.let { it.startsWith("Failed") || it.startsWith("Not a") || it.startsWith("Backup schema") } == true && !state.backupBusy
                Text(status!!, style = MaterialTheme.typography.bodySmall, color = if (failed) Tokens.Danger else Tokens.Muted)
            }

            Gap()
            SectionLabel("About")
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Workout Tracker", style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
                    Text(vm.appVersion, style = MonoNumberLarge, color = Tokens.Muted)
                }
                Text("Fully offline. No data leaves this device.", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
                Text("Fonts: IBM Plex, OFL", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
            }
        }
    }

    pendingImport?.let { uri ->
        ImportModeDialog(
            onReplace = { vm.importJson(uri, ImportMode.REPLACE); pendingImport = null },
            onMerge = { vm.importJson(uri, ImportMode.MERGE); pendingImport = null },
            onDismiss = { pendingImport = null }
        )
    }
}

@Composable
private fun Gap() = Spacer(Modifier.height(6.dp))

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Border))

@Composable
private fun CounterRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Tokens.Text, modifier = Modifier.weight(1f))
        StepButton("−", onMinus)
        Text(value, style = MonoNumberLarge, color = Tokens.Text, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 52.dp))
        StepButton("+", onPlus)
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Tokens.Surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.headlineSmall, color = Tokens.Text)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Tokens.Text, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Tokens.AccentInk, checkedTrackColor = Tokens.Accent, checkedBorderColor = Tokens.Accent,
                uncheckedThumbColor = Tokens.Muted, uncheckedTrackColor = Tokens.Surface2, uncheckedBorderColor = Tokens.BorderStrong
            )
        )
    }
}

@Composable
private fun ImportModeDialog(onReplace: () -> Unit, onMerge: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Surface,
        titleContentColor = Tokens.Text,
        textContentColor = Tokens.Muted,
        title = { Text("Import backup", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Replace everything deletes what is on this device first. Merge keeps existing data and adds rows that are missing.",
                    style = MaterialTheme.typography.bodyLarge
                )
                SecondaryButton("Replace everything", onReplace, Modifier.fillMaxWidth(), contentColor = Tokens.Danger)
                SecondaryButton("Merge", onMerge, Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Tokens.TextSoft, style = MaterialTheme.typography.labelLarge) }
        }
    )
}
