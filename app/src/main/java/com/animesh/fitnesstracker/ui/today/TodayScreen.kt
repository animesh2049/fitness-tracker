package com.animesh.fitnesstracker.ui.today

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AccentChipButton
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.BottomActionBar
import com.animesh.fitnesstracker.ui.components.EmptyState
import com.animesh.fitnesstracker.ui.components.OutlineChipButton
import com.animesh.fitnesstracker.ui.components.PillTag
import com.animesh.fitnesstracker.ui.components.PrimaryButton
import com.animesh.fitnesstracker.ui.components.ScreenHeader
import com.animesh.fitnesstracker.ui.components.SecondaryButton
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.navigation.WorkoutSection
import com.animesh.fitnesstracker.ui.navigation.WorkoutSectionRow
import com.animesh.fitnesstracker.ui.theme.Tokens

@Composable
fun TodayScreen(
    onOpenSession: (Long) -> Unit,
    onOpenPlan: () -> Unit,
    onOpenSettings: () -> Unit,
    onSection: (WorkoutSection) -> Unit = {}
) {
    val container = appContainer()
    val vm: TodayViewModel = viewModel { TodayViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.sessionStarted.collect { onOpenSession(it) } }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startSession() }
    val start: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.startSession()
    }

    val settingsButton: @Composable () -> Unit = {
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = Tokens.Muted)
        }
    }
    val sections: @Composable () -> Unit = { WorkoutSectionRow(WorkoutSection.Today, onSection) }
    Column(Modifier.fillMaxSize()) {
        when (val ui = state.ui) {
            TodayUi.Loading -> Spacer(Modifier.weight(1f))
            TodayUi.NoRoutine -> {
                ScreenHeader(state.dateLine, "No routine", trailing = settingsButton, below = sections)
                EmptyState(
                    "Nothing scheduled", "Create a routine under Plan to see what to do each day.",
                    modifier = Modifier.weight(1f)
                ) { SecondaryButton("Open Plan", onOpenPlan) }
            }
            is TodayUi.Rest -> {
                ScreenHeader(state.dateLine, ui.title, ui.subtitle, trailing = settingsButton, below = sections)
                if (state.showSwap) {
                    SwapPicker(state, vm, Modifier.weight(1f))
                } else {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Outlined.NightsStay, contentDescription = null, tint = Tokens.Muted, modifier = Modifier.height(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(ui.headline, style = MaterialTheme.typography.headlineSmall, color = Tokens.Text, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(ui.body, style = MaterialTheme.typography.bodyLarge, color = Tokens.Muted, textAlign = TextAlign.Center)
                    }
                    BottomActionBar {
                        if (state.inProgress != null) PrimaryButton("Resume session", vm::resumeSession)
                        if (ui.showDoneResting) PrimaryButton("Done resting", vm::doneResting)
                        SecondaryButton("Train anyway", vm::openSwap, Modifier.fillMaxWidth())
                    }
                }
            }
            is TodayUi.Workout -> {
                ScreenHeader(state.dateLine, if (state.showSwap) "Swap workout" else ui.title, if (state.showSwap) "Scheduled: ${ui.title}" else ui.subtitle, trailing = settingsButton, below = sections)
                if (state.showSwap) {
                    SwapPicker(state, vm, Modifier.weight(1f))
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (state.inProgress != null) {
                            item {
                                AppCard(borderColor = Tokens.AccentBorder, background = Tokens.AccentSurface) {
                                    Text("Session in progress", style = MaterialTheme.typography.titleMedium, color = Tokens.Accent)
                                    Text("${state.inProgress!!.session.groupName} was started and not finished.", style = MaterialTheme.typography.bodySmall, color = Tokens.AccentText)
                                }
                            }
                        }
                        items(ui.cards, key = { it.groupExerciseId }) { card -> ExerciseCardView(card, vm) }
                    }
                    BottomActionBar {
                        if (state.inProgress != null) PrimaryButton("Resume session", vm::resumeSession) else PrimaryButton("Start session", start)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Skip today", vm::skipToday, Modifier.weight(1f), height = 44)
                            SecondaryButton("Rest today", vm::restToday, Modifier.weight(1f), height = 44)
                            SecondaryButton("Swap", vm::openSwap, Modifier.weight(1f), height = 44)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseCardView(card: ExerciseCard, vm: TodayViewModel) {
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.ge.exercise.name, style = MaterialTheme.typography.titleLarge, color = Tokens.Text)
                Text(card.summary, style = MonoNumber, color = Tokens.Muted)
            }
            PillTag(card.typeLabel)
        }
        val s = card.suggestion
        when {
            s == null -> Unit
            card.pending -> {
                AppCard(borderColor = Tokens.AccentBorder, background = Tokens.AccentSurface, padding = PaddingValues(12.dp)) {
                    Text(s.reason, style = MaterialTheme.typography.bodySmall, color = Tokens.AccentText)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Try ${card.suggestionLabel}", style = MaterialTheme.typography.titleMedium, color = Tokens.Accent, modifier = Modifier.weight(1f))
                        AccentChipButton("Accept", { vm.accept(card.groupExerciseId) })
                        OutlineChipButton("Keep", { vm.keep(card.groupExerciseId) })
                    }
                }
            }
            s.isActionable && card.decision == Decision.ACCEPTED ->
                Text("Updated to ${card.suggestionLabel} for today", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = Tokens.Accent)
            s.isActionable && card.decision == Decision.KEPT ->
                Text("Keeping current targets", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
            else -> Text(s.reason, style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
        }
    }
}

@Composable
private fun SwapPicker(state: TodayState, vm: TodayViewModel, modifier: Modifier) {
    Column(modifier) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Pick a day to do now. The cycle continues from the next scheduled slot.",
                    style = MaterialTheme.typography.bodySmall, color = Tokens.Muted, modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            items(state.swapOptions, key = { it.group.group.id }) { opt ->
                AppCard(onClick = { vm.pickSwap(opt.group.group.id) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(opt.group.group.name, style = MaterialTheme.typography.titleLarge, color = Tokens.Text)
                            Text(opt.meta, style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
                        }
                        Text("Do today", style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = Tokens.Accent)
                    }
                }
            }
        }
        BottomActionBar { SecondaryButton("Cancel", vm::cancelSwap, Modifier.fillMaxWidth()) }
    }
}
