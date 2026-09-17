package com.animesh.fitnesstracker.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.GhostButton
import com.animesh.fitnesstracker.ui.components.ProgressBar
import com.animesh.fitnesstracker.ui.components.StatTile
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.Tokens

@Composable
fun SleepScreen(epochDay: Long, onBack: () -> Unit, onOpenTrends: () -> Unit) {
    val container = appContainer()
    val vm: SleepViewModel = viewModel(key = "sleep_$epochDay") { SleepViewModel(container, epochDay) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        HealthBackHeader(state.eyebrow, state.title, state.subtitle, onBack)
        if (state.loading) {
            Spacer(Modifier.weight(1f))
            return@Column
        }
        DaySwitcher(
            label = state.nightLabel,
            onPrevious = vm::previousNight,
            onNext = vm::nextNight,
            previousEnabled = state.canGoBack,
            nextEnabled = state.canGoForward,
            previousDescription = "Previous night",
            nextDescription = "Next night",
            showCalendar = false,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        )
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!state.hasNight) {
                item("none") { DashedNotice("No sleep recorded for this night. The watch writes a sleep file each morning; sync after waking.") }
            } else {
                item("stages") {
                    AppCard(padding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CardHeader("Stages", null, Modifier.padding(horizontal = 4.dp), captionContent = {
                                state.scoreLabel?.let { AccentPill(it, height = 22) }
                            })
                            if (state.bars.isEmpty()) DashedNotice("Stage detail has not been synced for this night")
                            else Hypnogram(state.bars, state.ticks)
                        }
                    }
                }
                item("rows") {
                    AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                        Column {
                            state.rows.forEachIndexed { i, row ->
                                StageRowView(row)
                                if (i < state.rows.lastIndex) HorizontalDivider(color = Tokens.Surface2, thickness = 1.dp)
                            }
                        }
                    }
                }
                item("tiles") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.tiles.chunked(3).forEach { rowTiles ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowTiles.forEach { t -> StatTile(t.label, t.value, t.sub, Modifier.weight(1f), valueStyle = MonoTile) }
                                repeat(3 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                item("note") {
                    NoteCard(
                        "From the watch",
                        "Stages, score and HRV status are computed on the Forerunner and read from its sleep and HRV files. The app does not re-score sleep."
                    )
                }
            }
            item("trends") { GhostButton("Sleep trends", onOpenTrends, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun StageRowView(row: StageRow) {
    Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val color = HealthColors.HypnogramLanes[row.lane.coerceIn(0, 3)]
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(row.name, style = MaterialTheme.typography.titleSmall, color = Tokens.Text, modifier = Modifier.width(48.dp))
        ProgressBar(row.fraction, Modifier.weight(1f), color = color)
        Text(row.duration, style = MonoNumber.copy(fontWeight = FontWeight.SemiBold), color = Tokens.Text, textAlign = TextAlign.End, modifier = Modifier.width(78.dp), maxLines = 1)
        Text(row.percent, style = MonoNumber.copy(fontSize = 12.sp), color = Tokens.Muted, textAlign = TextAlign.End, modifier = Modifier.width(36.dp))
    }
}
