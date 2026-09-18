package com.animesh.fitnesstracker.ui.watch

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.garmin.sync.BluetoothPermissions
import com.animesh.fitnesstracker.garmin.sync.SyncLogLine
import com.animesh.fitnesstracker.garmin.sync.WatchInfo
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.ConfirmDialog
import com.animesh.fitnesstracker.ui.components.GhostButton
import com.animesh.fitnesstracker.ui.components.PrimaryButton
import com.animesh.fitnesstracker.ui.components.ProgressBar
import com.animesh.fitnesstracker.ui.components.SectionLabel
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.MonoNumberLarge
import com.animesh.fitnesstracker.ui.theme.PlexMono
import com.animesh.fitnesstracker.ui.theme.Tokens
import com.animesh.fitnesstracker.util.Dates

private val LogSurface = Color(0xFF121417)
private const val LOG_LINES_SHOWN = 40
private val BACKGROUND_HOURS = listOf(1, 2, 4, 8)

/** Settings, Watch: pairing, sync, automatic sync toggles, the FIT archive and the sync log. */
@Composable
fun WatchScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm: WatchViewModel = viewModel { WatchViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var confirmForget by remember { mutableStateOf(false) }
    var confirmReimport by remember { mutableStateOf(false) }
    // Which action asked for the Bluetooth permissions, so the result knows what to continue with.
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val next = afterPermission
        afterPermission = null
        if (result.values.all { it }) next?.invoke() else vm.permissionDenied()
    }
    fun withPermissions(action: () -> Unit) {
        if (BluetoothPermissions.granted(context)) action() else {
            afterPermission = action
            permissions.launch(BluetoothPermissions.required(Build.VERSION.SDK_INT).toTypedArray())
        }
    }

    val today = Dates.date(Dates.todayEpochDay()).toString()
    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::exportZip) }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importFiles(uris) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Tokens.TextSoft)
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f).padding(start = 4.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SETTINGS", style = MaterialTheme.typography.labelMedium, color = Tokens.Muted)
                Text("Watch", style = MaterialTheme.typography.headlineLarge, color = Tokens.Text)
                if (state.loaded) Text(state.subtitle, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
            }
        }

        if (!state.loaded) {
            Spacer(Modifier.weight(1f))
            return@Column
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val watch = state.watch
            if (watch == null) {
                UnpairedContent(
                    state = state,
                    onScan = { withPermissions(vm::startScan) },
                    onStopScan = vm::stopScan,
                    onPair = vm::pair,
                    onOpenBluetoothSettings = { openBluetoothSettings(context) }
                )
                DataSection(
                    state,
                    onExport = { exportZip.launch("garmin-fit-$today.zip") },
                    onImport = { importFiles.launch(arrayOf("*/*")) },
                    onReimport = { confirmReimport = true }
                )
            } else {
                PairedContent(
                    state = state,
                    watch = watch,
                    onSync = { withPermissions(vm::syncNow) },
                    onCancelSync = vm::cancelSync,
                    onOpenBluetoothSettings = { openBluetoothSettings(context) },
                    onAutoSyncOnOpen = vm::setAutoSyncOnOpen,
                    onBackgroundHours = vm::setBackgroundSyncHours,
                    onKeepConnected = vm::setKeepConnected,
                    onExport = { exportZip.launch("garmin-fit-$today.zip") },
                    onImport = { importFiles.launch(arrayOf("*/*")) },
                    onReimport = { confirmReimport = true },
                    onShareLog = { shareLog(context, vm.logText()) },
                    onForget = { confirmForget = true }
                )
            }
        }
    }

    if (confirmForget) {
        ConfirmDialog(
            title = "Forget watch?",
            body = "Removes the pairing. Imported data stays.",
            confirmText = "Forget",
            destructive = true,
            onConfirm = { confirmForget = false; vm.forgetWatch() },
            onDismiss = { confirmForget = false }
        )
    }
    if (confirmReimport) {
        ConfirmDialog(
            title = "Re-import all stored files?",
            body = "Clears the imported health data and reads every stored FIT file again (${state.filesLabel} files). Workouts and diet data are not touched. This can take a few minutes.",
            confirmText = "Re-import",
            onConfirm = { confirmReimport = false; vm.reimportAll() },
            onDismiss = { confirmReimport = false }
        )
    }
}

// Unpaired

@Composable
private fun UnpairedContent(
    state: WatchScreenState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onPair: (ScanRow) -> Unit,
    onOpenBluetoothSettings: () -> Unit
) {
    AppCard(padding = PaddingValues(16.dp)) {
        Text("Pair your Garmin", style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
        Text(
            "The watch talks to this app over Bluetooth only. No Garmin account, no Garmin Connect, no internet. Steps, heart rate, sleep, Body Battery, stress and recorded activities are read from the watch's own files.",
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp), color = Tokens.Muted
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
            StepLine(1, "Finish the watch's own setup and enter your profile on the watch.")
            StepLine(2, "If it was ever paired with another phone app, remove that phone on the watch.")
            StepLine(3, "Open Settings, Phone, Pair phone on the watch. Ignore the QR code.")
        }
    }

    if (state.scanned || state.hits.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Nearby Garmin watches")
                Text(
                    if (state.scanning) "Scanning…" else if (state.hits.isEmpty()) "None found" else "Scan finished",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Dim, modifier = Modifier.padding(end = 4.dp)
                )
            }
            state.hits.forEach { hit -> ScanHitRow(hit, pairing = state.pairing, onPair = { onPair(hit) }) }
            if (state.scanning && state.hits.isEmpty()) {
                AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                    Text("Looking for a watch in pairing mode. This takes up to 20 seconds.", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
                }
            }
        }
    }

    state.message?.let { StatusLine(it, error = state.messageIsError) }
    if (state.bluetoothOff) GhostButton("Open Bluetooth settings", onOpenBluetoothSettings, Modifier.fillMaxWidth())

    when {
        state.pairing != null -> PrimaryButton("Pairing…", {}, enabled = false)
        state.scanning -> GhostButton("Stop scanning", onStopScan, Modifier.fillMaxWidth(), height = 48)
        else -> PrimaryButton(if (state.scanned) "Scan again" else "Scan for watches", onScan)
    }
}

@Composable
private fun StepLine(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$n", style = MonoNumber.copy(fontSize = 13.sp), color = Tokens.Accent, modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Tokens.TextSoft)
    }
}

@Composable
private fun ScanHitRow(hit: ScanRow, pairing: String?, onPair: () -> Unit) {
    val busy = pairing != null
    AppCard(padding = PaddingValues(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(hit.name, style = MaterialTheme.typography.titleSmall, color = Tokens.Text)
                Text("${hit.address} · signal ${hit.signal}", style = MonoNumber.copy(fontSize = 12.sp), color = Tokens.Muted)
            }
            PillButton(if (pairing == hit.address) "Pairing…" else "Pair", onPair, enabled = !busy)
        }
    }
}

/** The design's 40 dp accent pill; the row around it gives the full 44 dp touch height. */
@Composable
private fun PillButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(40.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Tokens.Accent, contentColor = Tokens.AccentInk,
            disabledContainerColor = Tokens.Surface2, disabledContentColor = Tokens.Dim
        )
    ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp)) }
}

// Paired

@Composable
private fun PairedContent(
    state: WatchScreenState,
    watch: WatchInfo,
    onSync: () -> Unit,
    onCancelSync: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onAutoSyncOnOpen: (Boolean) -> Unit,
    onBackgroundHours: (Int) -> Unit,
    onKeepConnected: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onReimport: () -> Unit,
    onShareLog: () -> Unit,
    onForget: () -> Unit
) {
    StatusCard(state, watch)

    if (state.syncing) {
        SyncingCard(state, onCancelSync)
    } else {
        PrimaryButton("Sync now", onSync)
    }
    state.syncOutcome?.let { if (!state.syncing) StatusLine(it, error = state.syncFailed) }
    state.message?.let { StatusLine(it, error = state.messageIsError) }
    if (state.bluetoothOff) GhostButton("Open Bluetooth settings", onOpenBluetoothSettings, Modifier.fillMaxWidth())

    SectionLabel("Automatic sync", Modifier.padding(top = 6.dp))
    AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)) {
        Column {
            ToggleRow("On app open", "When the last sync is older than an hour", watch.autoSyncOnOpen, onAutoSyncOnOpen)
            Divider()
            val background = watch.backgroundSyncHours > 0
            ToggleRow(
                "In the background",
                "Every ${if (background) watch.backgroundSyncHours else 2} ${if (background && watch.backgroundSyncHours == 1) "hour" else "hours"} when the watch is in range",
                background
            ) { on -> onBackgroundHours(if (on) 2 else 0) }
            if (background) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BACKGROUND_HOURS.forEach { h -> HourChip(h, selected = watch.backgroundSyncHours == h, onClick = { onBackgroundHours(h) }, modifier = Modifier.weight(1f)) }
                }
            }
            Divider()
            ToggleRow("Stay connected during sessions", "Shows live heart rate while you train (coming later)", watch.keepConnectedDuringSessions, onKeepConnected)
        }
    }

    DataSection(state, onExport, onImport, onReimport)

    SectionLabel("Last sync log", Modifier.padding(top = 6.dp))
    LogCard(state.log)
    GhostButton("Share log", onShareLog, Modifier.fillMaxWidth())

    Spacer(Modifier.height(6.dp))
    GhostButton("Forget watch", onForget, Modifier.fillMaxWidth(), height = 48, contentColor = Tokens.Danger, borderColor = Tokens.DangerBorder)
    Text(
        "Removes the pairing. Imported data stays.",
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Dim, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

/** Export, import and re-import rows. Shown paired or not, so USB copies of the watch's files can be loaded without Bluetooth. */
@Composable
private fun DataSection(state: WatchScreenState, onExport: () -> Unit, onImport: () -> Unit, onReimport: () -> Unit) {
    SectionLabel("Data", Modifier.padding(top = 6.dp))
    AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)) {
        Column {
            ActionRow("Export watch data", "zip of FIT files", enabled = !state.dataBusy, onClick = onExport)
            Divider()
            ActionRow("Import watch data", "zip or FIT files", enabled = !state.dataBusy, onClick = onImport)
            Divider()
            ActionRow("Re-import all stored files", "${state.filesLabel} ${WatchViewModel.plural(state.fileCount, "file")}", enabled = !state.dataBusy && state.fileCount > 0, onClick = onReimport)
        }
    }
    when {
        state.dataBusy -> StatusLine("Working…", error = false)
        state.dataStatus != null -> StatusLine(state.dataStatus, error = state.dataStatus.startsWith("Failed"))
    }
}

@Composable
private fun StatusCard(state: WatchScreenState, watch: WatchInfo) {
    AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Tokens.Surface2), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Watch, contentDescription = null, tint = Tokens.Accent, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(watch.name, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), color = Tokens.Text)
                Text(
                    watch.macAddress + (watch.firmwareVersion?.let { " · fw $it" } ?: ""),
                    style = MonoNumber.copy(fontSize = 12.sp), color = Tokens.Muted
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(watch.lastBatteryPercent?.let { "$it%" } ?: "?", style = MonoNumberLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = Tokens.Text)
                Text("battery", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = Tokens.Dim)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Surface2))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat("Last sync", state.lastSyncLabel, Modifier.weight(1f))
            MiniStat("Files stored", state.filesLabel, Modifier.weight(1f))
            MiniStat("Data since", state.dataSinceLabel, Modifier.weight(1f))
        }
        state.lastSentLabel?.let { sent ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Surface2))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Last sent", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = Tokens.Muted)
                Text(sent, style = MonoNumber.copy(fontSize = 12.sp), color = Tokens.TextSoft, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = Tokens.Muted)
        Text(value, style = MonoNumber.copy(fontWeight = FontWeight.SemiBold), color = Tokens.Text, maxLines = 1)
    }
}

@Composable
private fun SyncingCard(state: WatchScreenState, onCancel: () -> Unit) {
    AppCard(borderColor = Tokens.AccentBorder, background = Tokens.AccentSurface, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text("Syncing", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = Tokens.Text)
            Text(state.progressLabel, style = MonoNumber.copy(fontSize = 12.sp), color = Tokens.Muted)
        }
        ProgressBar(state.progressFraction)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(state.currentFileLabel, style = MonoNumber.copy(fontSize = 12.sp), color = Tokens.TextSoft, modifier = Modifier.weight(1f).padding(end = 8.dp))
            GhostButton("Cancel", onCancel, contentColor = Tokens.Accent, borderColor = Tokens.AccentBorder)
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Tokens.Text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Muted)
        }
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
private fun HourChip(hours: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Tokens.AccentSurface else Tokens.Surface2)
            .then(if (selected) Modifier.border(1.dp, Tokens.AccentBorder, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("$hours h", style = MonoNumber, color = if (selected) Tokens.Accent else Tokens.TextSoft)
    }
}

@Composable
private fun ActionRow(title: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).alpha(if (enabled) 1f else 0.45f).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Tokens.Text, modifier = Modifier.weight(1f))
        Text(hint, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Muted)
    }
}

@Composable
private fun LogCard(lines: List<SyncLogLine>) {
    val shown = lines.takeLast(LOG_LINES_SHOWN)
    AppCard(background = LogSurface, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (shown.isEmpty()) {
                Text("No sync yet.", style = MaterialTheme.typography.bodySmall.copy(fontFamily = PlexMono, fontSize = 11.sp, lineHeight = 16.sp), color = Tokens.Muted)
            }
            shown.forEach { line ->
                Text(
                    "${logTime(line.atMillis)} ${line.text}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
                    color = Tokens.TextSoft
                )
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, error: Boolean) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) Tokens.Danger else Tokens.Muted, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Surface2))

private fun openBluetoothSettings(context: android.content.Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun shareLog(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Fitness Tracker sync log")
        .putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Share sync log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
