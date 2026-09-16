package com.animesh.fitnesstracker.ui.progress

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.EmptyState
import com.animesh.fitnesstracker.ui.components.PillTag
import com.animesh.fitnesstracker.ui.components.SectionLabel
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.Tokens

@Composable
fun ExerciseHistoryScreen(exerciseId: Long, onBack: () -> Unit) {
    val container = appContainer()
    val vm: ExerciseHistoryViewModel = viewModel { ExerciseHistoryViewModel(container, exerciseId) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        HistoryHeader(
            title = state.name.ifEmpty { "Exercise" },
            subtitle = "${state.sessionCount} ${if (state.sessionCount == 1) "session" else "sessions"}",
            onBack = onBack
        )
        when {
            state.loading -> Spacer(Modifier.weight(1f))
            state.sessions.isEmpty() -> EmptyState("No sets logged", "Sets from finished sessions show up here.", modifier = Modifier.weight(1f))
            else -> LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.sessions, key = { it.sessionId }) { session ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        SectionLabel(session.dateLabel)
                        AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                            session.sets.forEach { SetRowView(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Tokens.Text)
        }
        Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ALL LOGGED SETS", style = MaterialTheme.typography.labelMedium, color = Tokens.Muted)
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Tokens.Text)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
        }
    }
}

@Composable
private fun SetRowView(row: SetRow) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${row.number}", style = MonoNumber, color = Tokens.Dim, modifier = Modifier.width(22.dp))
        Text(row.value, style = MonoNumber, color = if (row.outcome == SetOutcome.SKIPPED) Tokens.Muted else Tokens.Text, modifier = Modifier.weight(1f))
        if (row.warmup) PillTag("Warm-up")
        when (row.outcome) {
            SetOutcome.HIT -> PillTag("Hit", color = Tokens.Accent, borderColor = Tokens.AccentBorder)
            SetOutcome.MISSED -> PillTag("Missed", color = Tokens.Danger, borderColor = Tokens.DangerBorder)
            SetOutcome.SKIPPED -> PillTag("Skipped", color = Tokens.Dim, borderColor = Tokens.Border)
        }
    }
}
