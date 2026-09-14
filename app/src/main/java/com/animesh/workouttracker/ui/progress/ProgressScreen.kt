package com.animesh.workouttracker.ui.progress

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.workouttracker.ui.appContainer
import com.animesh.workouttracker.ui.components.AppCard
import com.animesh.workouttracker.ui.components.EmptyState
import com.animesh.workouttracker.ui.components.OutlineChipButton
import com.animesh.workouttracker.ui.components.ScreenHeader
import com.animesh.workouttracker.ui.components.SectionLabel
import com.animesh.workouttracker.ui.theme.MonoNumber
import com.animesh.workouttracker.ui.theme.MonoStat
import com.animesh.workouttracker.ui.theme.Tokens

@Composable
fun ProgressScreen(onOpenHistory: (Long) -> Unit) {
    val container = appContainer()
    val vm: ProgressViewModel = viewModel { ProgressViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        val exercise = state.exercise
        when {
            state.loading -> Spacer(Modifier.weight(1f))
            exercise == null -> {
                ScreenHeader("Progress", "No history")
                EmptyState("No history yet", "Finish a session and your progress shows up here.", modifier = Modifier.weight(1f))
            }
            else -> {
                ScreenHeader("Progress", exercise.name, state.subtitle)
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item(key = "chips") { ChipRow(state.chips, vm::selectExercise) }
                    val chart = state.chart
                    if (chart != null) {
                        item(key = "chart") { ChartCard(chart, vm::selectPoint) }
                    }
                    if (state.stats.isNotEmpty()) {
                        item(key = "stats") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.stats.forEach { s -> ProgressStatTile(s, Modifier.weight(1f)) }
                            }
                        }
                    }
                    if (state.records.isNotEmpty()) {
                        item(key = "records-label") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SectionLabel("Personal records")
                                state.records.forEach { RecordCard(it) }
                            }
                        }
                    }
                    item(key = "all-sets") {
                        TextButton(
                            onClick = { onOpenHistory(exercise.id) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        ) {
                            Text("All logged sets", style = MaterialTheme.typography.labelLarge, color = Tokens.Accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(chips: List<ExerciseChip>, onPick: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { chip ->
            if (chip.selected) {
                Button(
                    onClick = { onPick(chip.id) },
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Tokens.Text, contentColor = Tokens.AccentInk)
                ) { Text(chip.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)) }
            } else {
                OutlineChipButton(chip.name, { onPick(chip.id) }, contentColor = Tokens.TextSoft, borderColor = Tokens.BorderStrong)
            }
        }
    }
}

@Composable
private fun ChartCard(chart: ChartData, onSelect: (Int) -> Unit) {
    AppCard(padding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(chart.title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = Tokens.Text)
            Text(chart.rangeText, style = MonoNumber.copy(fontSize = 12.sp), color = Tokens.Muted)
        }
        ProgressChart(chart, onSelect)
    }
}

/** Like StatTile but with the design's 20 sp value so three tiles fit side by side. */
@Composable
private fun ProgressStatTile(stat: StatData, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier, padding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stat.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Tokens.Muted)
            val size = if (stat.value.length > 7) 16.sp else 20.sp
            Text(stat.value, style = MonoStat.copy(fontSize = size, lineHeight = 24.sp), color = Tokens.Text, maxLines = 1)
            Text(stat.sub, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Normal), color = Tokens.Dim, maxLines = 1)
        }
    }
}

@Composable
private fun RecordCard(record: RecordRow) {
    AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(record.label, style = MaterialTheme.typography.titleSmall, color = Tokens.Text)
                Text(record.date, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Muted)
            }
            Text(record.value, style = MonoNumber.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold), color = Tokens.Text)
        }
    }
}
