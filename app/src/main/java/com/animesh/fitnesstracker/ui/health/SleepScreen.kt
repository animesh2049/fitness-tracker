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
                if (state.breakdown.isNotEmpty()) {
                    item("breakdown") {
                        AppCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
                            Column {
                                CardHeader("Score breakdown", state.breakdownCaption, Modifier.padding(bottom = 6.dp))
                                state.breakdown.forEachIndexed { i, row ->
                                    BreakdownRowView(row)
                                    if (i < state.breakdown.lastIndex) HorizontalDivider(color = Tokens.Surface2, thickness = 1.dp)
                                }
                            }
                        }
                    }
                }
                state.need?.let { need ->
                    item("need") { SleepNeedCard(need) }
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
                        "Stages, scores, sleep need and HRV status are computed on the Forerunner and read from its sleep, metrics and HRV files. The app does not re-score sleep."
                    )
                }
            }
            item("trends") { GhostButton("Sleep trends", onOpenTrends, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun BreakdownRowView(row: BreakdownRow) {
    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(96.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp), color = Tokens.Text, maxLines = 1)
            if (row.detail != null) Text(row.detail, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Normal), color = Tokens.Dim, maxLines = 1)
        }
        ProgressBar(row.fraction, Modifier.weight(1f), color = row.color)
        Text(row.score, style = MonoNumber.copy(fontWeight = FontWeight.SemiBold), color = Tokens.Text, textAlign = TextAlign.End, modifier = Modifier.width(34.dp))
        Text(row.band, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Normal), color = Tokens.Muted, textAlign = TextAlign.End, modifier = Modifier.width(60.dp), maxLines = 1)
    }
}

@Composable
private fun SleepNeedCard(need: NeedUi) {
    AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader("Sleep need", "Sleep Coach")
            Row(Modifier.fillMaxWidth()) {
                NeedCell("Need", need.need, Tokens.Text, Modifier.weight(1f))
                NeedCell("Slept", need.slept, Tokens.Text, Modifier.weight(1f))
                NeedCell(if (need.met) "Need" else "Short by", if (need.met) "Met" else need.shortBy, if (need.met) Tokens.Accent else Tokens.Warning, Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.CenterStart) {
                ProgressBar(need.fraction, Modifier.fillMaxWidth(), color = if (need.met) Tokens.Accent else HealthColors.BarDim)
                // The need marker sits at the right edge: the bar is the night as a share of the need.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.width(2.dp).height(12.dp).background(Tokens.Text))
                }
            }
            if (need.baselineLine != null) {
                Text(need.baselineLine, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp), color = Tokens.Muted)
            }
        }
    }
}

@Composable
private fun NeedCell(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Tokens.Muted)
        Text(value, style = MonoTile, color = color, maxLines = 1)
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
