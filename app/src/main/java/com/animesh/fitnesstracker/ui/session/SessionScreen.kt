package com.animesh.fitnesstracker.ui.session

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.domain.timer.TimerKind
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AccentChipButton
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.BottomActionBar
import com.animesh.fitnesstracker.ui.components.ConfirmDialog
import com.animesh.fitnesstracker.ui.components.GhostButton
import com.animesh.fitnesstracker.ui.components.PrimaryButton
import com.animesh.fitnesstracker.ui.components.ProgressBar
import com.animesh.fitnesstracker.ui.components.SecondaryButton
import com.animesh.fitnesstracker.ui.components.SectionLabel
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
    val cur = ui.currentState
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${session.session.groupName} · Exercise ${(ui.exerciseIndex + 1).coerceIn(0, ui.exerciseCount)} of ${ui.exerciseCount}".uppercase(),
                    style = MaterialTheme.typography.labelMedium, color = Tokens.Muted
                )
                GhostButton("End", onEnd, height = 36, contentColor = Tokens.Muted)
            }
            ExerciseSegments(ui.exerciseStates, onPick = vm::jumpToExercise)
        }

        // Navigator: previous, name + state, next (FR56).
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavArrow(Icons.Outlined.ChevronLeft, "Previous exercise", enabled = ui.canGoPrevious, onClick = vm::previousExercise)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    cur?.name ?: "", style = MaterialTheme.typography.headlineSmall, color = Tokens.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                )
                Text(cur?.stateText ?: "", style = MaterialTheme.typography.bodySmall, color = cur?.let { stateColor(it) } ?: Tokens.Muted)
            }
            NavArrow(Icons.Outlined.ChevronRight, "Next exercise", enabled = ui.canGoNext, onClick = vm::nextExercise)
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Last time", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
                    Text(ui.lastTime, style = MonoNumber.copy(fontSize = 13.sp), color = Tokens.TextSoft)
                }
            }
            when {
                ui.cursorSkipped -> item { SkippedCard(onResume = vm::unskipExercise) }
                else -> {
                    item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { ui.rows.forEach { SetRowView(it) } } }
                    item {
                        when {
                            ui.current != null -> if (ui.isTimed) TimedSetCard(ui, vm) else WeightSetCard(ui, vm)
                            else -> CompleteCard(ui, vm)
                        }
                    }
                }
            }
            if (ui.allDone && !ui.cursorComplete) {
                item {
                    AppCard(borderColor = Tokens.AccentBorder, background = Tokens.AccentSurface) {
                        Text("Every set is logged.", style = MaterialTheme.typography.titleMedium, color = Tokens.Accent)
                        Text("Finish to save the session, update your targets, and move the cycle on.", style = MaterialTheme.typography.bodySmall, color = Tokens.AccentText)
                        PrimaryButton("Finish session", { vm.finish() }, height = 52)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Add set", vm::addSet, Modifier.weight(1f))
                    // Skipping a finished exercise would hide its logged sets from history, and a skipped one has Resume.
                    if (!ui.cursorComplete && !ui.cursorSkipped) GhostButton("Skip", vm::skipExercise, Modifier.weight(1f))
                }
            }
            item {
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Today's list")
                    ui.exerciseStates.forEach { s -> ExerciseListRow(s, highlighted = s.isCurrent, meta = s.stateText, metaColor = stateColor(s), onClick = { vm.jumpToExercise(s.id) }) }
                }
            }
        }
    }
}

/** Text colour for an exercise's state line: lime when complete, danger when skipped, muted before it starts. */
private fun stateColor(s: ExerciseState): Color = when {
    s.skipped -> Tokens.Danger
    s.complete -> Tokens.Accent
    s.started -> Tokens.TextSoft
    else -> Tokens.Muted
}

/** Dot colour for an exercise in the list: lime done, dim lime in progress, danger skipped, border untouched. */
private fun dotColor(s: ExerciseState): Color = when {
    s.skipped -> Tokens.Danger
    s.complete -> Tokens.Accent
    s.started -> Tokens.AccentDim
    else -> Tokens.BorderStrong
}

/** Progress segments per exercise, one tap target each: done lime, current dim lime, skipped danger tint, untouched border. */
@Composable
private fun ExerciseSegments(states: List<ExerciseState>, onPick: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        states.forEach { s ->
            val color = when {
                s.skipped -> Tokens.DangerBorder
                s.complete -> Tokens.Accent
                s.isCurrent -> Tokens.AccentDim
                s.started -> Tokens.AccentBorder
                else -> Tokens.Surface2
            }
            Box(
                Modifier.weight(1f).height(24.dp).clickable(onClick = { onPick(s.id) }).semantics { contentDescription = "Go to ${s.name}" },
                contentAlignment = Alignment.Center
            ) {
                // The current segment gets a thin outline 2 dp outside the bar, like the design.
                Box(
                    Modifier.fillMaxWidth().height(10.dp)
                        .then(if (s.isCurrent) Modifier.border(1.dp, Tokens.Accent, RoundedCornerShape(5.dp)) else Modifier)
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
                }
            }
        }
    }
}

/** 44 dp square arrow button on a surface tile; dimmed (and inert) at the ends. */
@Composable
private fun NavArrow(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(44.dp).clip(shape).background(Tokens.Surface).border(1.dp, Tokens.Border, shape),
        colors = IconButtonDefaults.iconButtonColors(contentColor = Tokens.Text, disabledContentColor = Tokens.Faint)
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun SkippedCard(onResume: () -> Unit) {
    AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Skipped earlier", style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
                Text("Nothing logged. Resume to log sets now; the skip is undone.", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
            }
            AccentChipButton("Resume", onResume)
        }
    }
}

/** Shown when the exercise on screen has no unlogged set left: result line and the way forward. */
@Composable
private fun CompleteCard(ui: SessionUi, vm: SessionViewModel) {
    val s = ui.currentState ?: return
    val nextName = ui.upNextExerciseId?.let { id -> ui.exerciseStates.firstOrNull { it.id == id }?.name }
    AppCard(padding = PaddingValues(16.dp)) {
        Text("${s.name} complete", style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
        Text("${s.done} of ${s.total} sets logged. Use the arrows or the list to revisit any exercise.", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
        when {
            ui.allDone || nextName == null -> PrimaryButton("Finish session", { vm.finish() }, height = 52)
            else -> PrimaryButton("Next: $nextName", { vm.jumpToExercise(ui.upNextExerciseId!!) }, height = 52)
        }
    }
}

/** One row of "Today's list": state dot, name, state text. 44 dp tall, highlighted when it is the one on screen. */
@Composable
private fun ExerciseListRow(s: ExerciseState, highlighted: Boolean, meta: String, metaColor: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (highlighted) Tokens.Surface else Color.Transparent)
            .border(1.dp, if (highlighted) Tokens.BorderStrong else Tokens.Surface2, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor(s)))
        Text(s.name, style = MaterialTheme.typography.titleSmall, color = Tokens.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(meta, style = MonoNumber.copy(fontSize = 12.sp), color = metaColor, maxLines = 1)
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

/**
 * Full-screen rest timer with navigation (FR58). The arrows and the list only peek at other
 * exercises; "Continue with ..." moves the cursor there while the rest keeps running.
 */
@Composable
private fun RestOverlay(ui: SessionUi, vm: SessionViewModel) {
    val cd = ui.countdown ?: return
    val session = ui.session ?: return
    val viewedIndex = ui.peekIndex ?: ui.exerciseIndex
    val viewed = ui.exerciseStates.getOrNull(viewedIndex)
    val peeking = ui.peekIndex != null
    Column(
        Modifier.fillMaxSize().background(Tokens.Ground).verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Rest · ${session.session.groupName}".uppercase(), style = MaterialTheme.typography.labelMedium, color = Tokens.Muted)
            Text("Exercise ${(ui.exerciseIndex + 1).coerceIn(0, ui.exerciseCount)} of ${ui.exerciseCount}", style = MonoNumber.copy(fontSize = 12.sp), color = Tokens.Muted)
        }

        Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(Dates.mmss(ui.remaining), style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp, lineHeight = 88.sp), color = Tokens.Text)
            ProgressBar(cd.fraction(ui.now))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("+30 s", vm::addRest30, Modifier.weight(1f), height = 52)
                PrimaryButton("Skip rest", vm::skipRest, Modifier.weight(1f), height = 52)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val label = when {
                !peeking -> "Up next"
                viewedIndex < ui.exerciseIndex -> "Earlier"
                else -> "Later"
            }
            SectionLabel(label)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavArrow(Icons.Outlined.ChevronLeft, "Peek at previous exercise", enabled = viewedIndex > 0, onClick = vm::peekPrevious, modifier = Modifier.fillMaxHeight())
                RestCard(viewed, peeking, Modifier.weight(1f))
                NavArrow(Icons.Outlined.ChevronRight, "Peek at next exercise", enabled = viewedIndex < ui.exerciseCount - 1, onClick = vm::peekNext, modifier = Modifier.fillMaxHeight())
            }
            if (peeking && viewed != null) {
                GhostButton("Continue with ${viewed.name} after this rest", vm::continueWithPeeked, Modifier.fillMaxWidth(), height = 48, contentColor = Tokens.Accent, borderColor = Tokens.AccentBorder)
            } else {
                Text(
                    "Arrows peek at the other exercises; the timer keeps running.",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = Tokens.Dim, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Today's list")
            ui.exerciseStates.forEachIndexed { i, s ->
                val meta = if (s.isCurrent) "now" else s.stateText
                ExerciseListRow(s, highlighted = i == viewedIndex, meta = meta, metaColor = if (s.isCurrent) Tokens.Accent else stateColor(s), onClick = { vm.peekExercise(s.id) })
            }
        }
    }
}

/** The "Up next" card: accent surface for the cursor exercise, neutral while peeking. */
@Composable
private fun RestCard(s: ExerciseState?, peeking: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (peeking) Tokens.Surface else Tokens.AccentSurface)
            .border(1.dp, if (peeking) Tokens.Border else Tokens.AccentBorder, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (s == null) return@Column
        Text(s.name, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), color = Tokens.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val line = when {
            s.skipped -> "Skipped"
            peeking -> if (s.target.isEmpty()) "" else "Target ${s.target}"
            s.nextSetIndex != null -> "Set ${s.nextSetIndex + 1} of ${s.total} · ${s.target}"
            else -> "All sets done"
        }
        Text(line, style = MonoNumber, color = Tokens.TextSoft)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(s.total) { k ->
                val color = when {
                    k < s.done -> Tokens.Accent
                    k == s.nextSetIndex && !peeking -> Tokens.AccentDim
                    else -> Tokens.Surface2
                }
                Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(color))
            }
        }
        val meta = when {
            s.skipped -> "Skipped earlier"
            s.complete -> "All sets done"
            else -> "${s.done} of ${s.total} sets done"
        }
        Text(meta, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Muted)
    }
}

@Composable
private fun WatchNumber(label: String, value: String, unit: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Tokens.AccentText)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, style = MonoNumber.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = Tokens.Text, maxLines = 1)
            if (unit != null) Text(unit, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = Tokens.AccentText, modifier = Modifier.padding(bottom = 1.dp))
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
            s.watch?.let { w ->
                item {
                    AppCard(borderColor = Tokens.AccentBorder, background = Tokens.AccentSurface, padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("From your watch", style = MaterialTheme.typography.titleSmall, color = Tokens.Accent)
                            Text(w.name, style = MaterialTheme.typography.bodySmall, color = Tokens.AccentText, maxLines = 1)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WatchNumber("Avg HR", w.avgHr?.toString() ?: "?", "bpm", Modifier.weight(1f))
                            WatchNumber("Max HR", w.maxHr?.toString() ?: "?", "bpm", Modifier.weight(1f))
                            WatchNumber("Calories", w.calories?.toString() ?: "?", "kcal", Modifier.weight(1f))
                            WatchNumber("Duration", w.duration, null, Modifier.weight(1f))
                        }
                    }
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
