package com.animesh.fitnesstracker.ui.session

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.domain.timer.TimerKind
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.BottomActionBar
import com.animesh.fitnesstracker.ui.components.ConfirmDialog
import com.animesh.fitnesstracker.ui.components.GhostButton
import com.animesh.fitnesstracker.ui.components.PrimaryButton
import com.animesh.fitnesstracker.ui.components.ProgressBar
import com.animesh.fitnesstracker.ui.components.ProgressSegments
import com.animesh.fitnesstracker.ui.components.SecondaryButton
import com.animesh.fitnesstracker.ui.components.StatTile
import com.animesh.fitnesstracker.ui.components.Stepper
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.MonoNumberLarge
import com.animesh.fitnesstracker.ui.theme.Tokens
import com.animesh.fitnesstracker.util.Dates
import com.animesh.fitnesstracker.util.Weights

@Composable
fun SessionScreen(sessionId: Long, onClose: () -> Unit) {
    val container = appContainer()
    val vm: SessionViewModel = viewModel(key = "session-$sessionId") { SessionViewModel(container, sessionId) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.closed.collect { onClose() } }
    LaunchedEffect(Unit) { vm.records.collect { snackbar.showSnackbar("New record: " + vm.recordText(it)) } }

    KeepScreenOn(ui.settings.keepScreenAwake && ui.summary == null)
    BackHandler(enabled = ui.summary == null) { confirmLeave = true }

    Box(Modifier.fillMaxSize().background(Tokens.Ground)) {
        when {
            ui.summary != null -> SummaryView(ui.summary!!, onDone = vm::close)
            ui.session == null -> Unit
            else -> ActiveView(ui, vm, onEnd = { confirmEnd = true })
        }
        if (ui.resting && ui.summary == null) RestOverlay(ui, vm)
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) { data ->
            Snackbar(containerColor = Tokens.Accent, contentColor = Tokens.AccentInk) { Text(data.visuals.message, style = MaterialTheme.typography.titleSmall) }
        }
    }

    if (confirmEnd) {
        ConfirmDialog(
            "End session?", "Everything logged so far is kept. Unfinished sets are dropped.",
            confirmText = "End", onConfirm = { confirmEnd = false; vm.abandon() }, onDismiss = { confirmEnd = false }, destructive = true
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            "Leave session?", "It stays in progress. Resume it from Today.",
            confirmText = "Leave", onConfirm = { confirmLeave = false; onClose() }, onDismiss = { confirmLeave = false }
        )
    }
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val context = LocalContext.current
    DisposableEffect(on) {
        val window = (context as? Activity)?.window
        if (on) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

@Composable
private fun ActiveView(ui: SessionUi, vm: SessionViewModel, onEnd: () -> Unit) {
    val session = ui.session ?: return
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${session.session.groupName} · Exercise ${(ui.exerciseIndex + 1).coerceAtMost(ui.exerciseCount)} of ${ui.exerciseCount}".uppercase(),
                    style = MaterialTheme.typography.labelMedium, color = Tokens.Muted
                )
                GhostButton("End", onEnd, height = 36, contentColor = Tokens.Muted)
            }
            ProgressSegments(total = ui.exerciseCount, done = ui.doneExercises, current = ui.exerciseIndex)
            if (ui.allDone) {
                Text("All exercises done", style = MaterialTheme.typography.headlineMedium, color = Tokens.Text)
            } else {
                Text(ui.exercise?.exercise?.exerciseName ?: "", style = MaterialTheme.typography.headlineMedium, color = Tokens.Text)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Last time", style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
                    Text(ui.lastTime, style = MonoNumber, color = Tokens.TextSoft)
                }
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (ui.allDone) {
                item {
                    AppCard(borderColor = Tokens.AccentBorder, background = Tokens.AccentSurface) {
                        Text("Every set is logged.", style = MaterialTheme.typography.titleMedium, color = Tokens.Accent)
                        Text("Finish to save the session, update your targets, and move the cycle on.", style = MaterialTheme.typography.bodySmall, color = Tokens.AccentText)
                        PrimaryButton("Finish session", { vm.finish() }, height = 52)
                    }
                }
            } else {
                item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { ui.rows.forEach { SetRowView(it) } } }
                item {
                    if (ui.isTimed) TimedSetCard(ui, vm) else WeightSetCard(ui, vm)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Add set", vm::addSet, Modifier.weight(1f))
                        GhostButton("Skip exercise", vm::skipExercise, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SetRowView(row: SetRow) {
    val (bg, border, textColor) = when (row.state) {
        RowState.DONE_HIT, RowState.DONE_MISSED -> Triple(Tokens.Ground, Tokens.Surface2, Tokens.TextSoft)
        RowState.CURRENT -> Triple(Tokens.Surface, Tokens.Accent, Tokens.Text)
        RowState.UPCOMING -> Triple(Color.Transparent, Tokens.Surface2, Tokens.Dim)
    }
    val badgeColor = when (row.state) {
        RowState.DONE_HIT, RowState.CURRENT -> Tokens.Accent
        RowState.DONE_MISSED -> Tokens.Danger
        RowState.UPCOMING -> Tokens.Dim
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(
                    when (row.state) { RowState.DONE_HIT, RowState.DONE_MISSED -> Tokens.Accent; RowState.CURRENT -> Tokens.Surface2; RowState.UPCOMING -> Tokens.Surface }
                ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${row.set.position + 1}", style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    color = when (row.state) { RowState.DONE_HIT, RowState.DONE_MISSED -> Tokens.AccentInk; RowState.CURRENT -> Tokens.Text; RowState.UPCOMING -> Tokens.Dim }
                )
            }
            Text(row.text, style = MonoNumberLarge, color = textColor)
        }
        Text(row.badge.uppercase(), style = MaterialTheme.typography.labelSmall, color = badgeColor)
    }
}

@Composable
private fun WeightSetCard(ui: SessionUi, vm: SessionViewModel) {
    val step = ui.current ?: return
    val edit = ui.edit ?: return
    val setCount = ui.exercise?.sets?.size ?: 0
    val hasWeight = step.set.targetWeightKg != null
    AppCard(padding = PaddingValues(16.dp), background = Tokens.Surface) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text("Set ${step.setIndex + 1} of $setCount" + if (step.set.isWarmup) " · warm-up" else "", style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
            Text("target ${ui.targetText}", style = MonoNumber.copy(fontSize = 13.sp), color = Tokens.Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hasWeight) {
                val stepKg = vm.weightStep()
                Stepper(
                    label = "Weight ${ui.unit.name.lowercase()}",
                    value = Weights.format(Weights.toDisplay(edit.weightKg ?: 0.0, ui.unit)),
                    onMinus = { vm.adjustWeight(-stepKg) }, onPlus = { vm.adjustWeight(stepKg) },
                    modifier = Modifier.weight(1f)
                )
            }
            Stepper(
                label = "Reps", value = "${edit.reps ?: 0}",
                onMinus = { vm.adjustReps(-1) }, onPlus = { vm.adjustReps(1) },
                modifier = Modifier.weight(1f)
            )
        }
        PrimaryButton("Done set", vm::doneSet)
    }
}

@Composable
private fun TimedSetCard(ui: SessionUi, vm: SessionViewModel) {
    val step = ui.current ?: return
    val cd = ui.countdown
    val setCount = ui.exercise?.sets?.size ?: 0
    val target = step.set.targetSeconds ?: 30
    val phase = cd?.kind?.takeIf { it != TimerKind.REST }
    val (label, big, sub, labelColor, fraction) = when (phase) {
        TimerKind.GET_READY -> Phase("Get ready", "${ui.remaining}", "get into position", Tokens.Warning, 0f)
        TimerKind.WORK -> Phase("Hold", "${ui.remaining}", "seconds left of $target", Tokens.Accent, cd!!.elapsedSeconds(ui.now).toFloat() / target)
        else -> Phase("Ready", "$target", "seconds · target for set ${step.setIndex + 1} of $setCount", Tokens.Muted, 0f)
    }
    AppCard(padding = PaddingValues(16.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = labelColor)
            Text(big, style = MaterialTheme.typography.displayLarge, color = if (phase == TimerKind.GET_READY) Tokens.Warning else Tokens.Text)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
            ProgressBar(fraction)
            when (phase) {
                null -> PrimaryButton("Start set ${step.setIndex + 1}", vm::startTimedSet, height = 60)
                TimerKind.GET_READY -> SecondaryButton("Cancel", vm::cancelTimedSet, Modifier.fillMaxWidth(), height = 60)
                TimerKind.WORK -> SecondaryButton("Stop early", vm::stopTimedSetEarly, Modifier.fillMaxWidth(), height = 60, contentColor = Tokens.Danger)
                TimerKind.REST -> Unit
            }
        }
    }
}

private data class Phase(val label: String, val big: String, val sub: String, val color: Color, val fraction: Float)

@Composable
private fun RestOverlay(ui: SessionUi, vm: SessionViewModel) {
    val cd = ui.countdown ?: return
    Column(
        Modifier.fillMaxSize().background(Tokens.Ground).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp)
    ) {
        Text("REST", style = MaterialTheme.typography.labelMedium, color = Tokens.Muted)
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(Dates.mmss(ui.remaining), style = MaterialTheme.typography.displayLarge, color = Tokens.Text)
            Spacer(Modifier.height(20.dp))
            ProgressBar(cd.fraction(ui.now))
            Spacer(Modifier.height(20.dp))
            Text("Up next", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
            Text(cd.label, style = MonoNumberLarge.copy(fontSize = 18.sp), color = Tokens.Text, textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("+30 s", vm::addRest30, Modifier.weight(1f), height = 56)
            PrimaryButton("Skip rest", vm::skipRest, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryView(s: SessionSummary, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("SESSION COMPLETE", style = MaterialTheme.typography.labelMedium, color = Tokens.Muted)
            Text(s.groupName, style = MaterialTheme.typography.headlineLarge, color = Tokens.Text)
            Text("${s.dateLine} · ${s.duration}", style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Sets", "${s.sets}", modifier = Modifier.weight(1f))
                    StatTile("Volume", s.volume, modifier = Modifier.weight(1f))
                    StatTile("Records", "${s.records}", modifier = Modifier.weight(1f), valueColor = if (s.records > 0) Tokens.Accent else Tokens.Text)
                }
            }
            items(s.rows.size) { i ->
                val r = s.rows[i]
                AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(r.name, style = MaterialTheme.typography.titleLarge.copy(fontSize = 15.sp), color = Tokens.Text)
                            Text(r.detail, style = MonoNumber.copy(fontSize = 13.sp), color = Tokens.Muted)
                        }
                        Text(if (r.hit) "All sets hit" else "Partial", style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold), color = if (r.hit) Tokens.Accent else Tokens.Muted)
                    }
                }
            }
            if (s.nextTime.isNotEmpty()) {
                item {
                    AppCard(borderColor = Tokens.AccentBorder, background = Tokens.AccentSurface, padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Next time", style = MaterialTheme.typography.titleSmall, color = Tokens.Accent)
                        s.nextTime.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.AccentText) }
                    }
                }
            }
        }
        BottomActionBar { PrimaryButton("Done", onDone) }
    }
}
