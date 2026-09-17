package com.animesh.fitnesstracker.ui.health

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.domain.health.TrendMetric
import com.animesh.fitnesstracker.domain.health.TrendPeriod
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.SegmentedRow
import com.animesh.fitnesstracker.ui.components.StatTile
import com.animesh.fitnesstracker.ui.theme.Tokens

@Composable
fun TrendsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm: TrendsViewModel = viewModel { TrendsViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        HealthBackHeader("Health · Trends", state.metricName, state.periodLabel.ifEmpty { null }, onBack)
        if (state.loading) {
            Spacer(Modifier.weight(1f))
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TrendMetric.entries, key = { it.name }) { m ->
                    PillChip(m.label, selected = m == state.metric, onClick = { vm.pickMetric(m) })
                }
            }
            SegmentedRow(
                options = TrendsViewModel.PERIOD_LABELS,
                selected = state.period.ordinal,
                onSelect = { vm.pickPeriod(TrendPeriod.entries[it]) },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            AppCard(Modifier.padding(horizontal = 16.dp), padding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardHeader(state.chartTitle, state.chartRange, Modifier.padding(horizontal = 4.dp))
                    if (!state.hasData) {
                        Box(Modifier.fillMaxWidth().height(210.dp), contentAlignment = Alignment.Center) {
                            Text("No ${state.metricName.lowercase()} data in this period yet", style = MaterialTheme.typography.bodySmall, color = Tokens.Dim)
                        }
                    } else {
                        TrendChart(
                            values = state.values,
                            labels = state.labels,
                            bars = state.bars,
                            selected = state.selected,
                            onSelect = vm::select,
                            min = state.min,
                            max = state.max,
                            gridLabels = state.gridLabels,
                            bubbleText = state.bubble
                        )
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Average", state.average, state.unitWord, Modifier.weight(1f), valueStyle = MonoTile)
                StatTile(state.bestWord, state.best, state.bestWhen.ifEmpty { "no data" }, Modifier.weight(1f), valueStyle = MonoTile)
                StatTile(
                    "Change", state.change, state.changeSub, Modifier.weight(1f),
                    valueColor = when (state.changeGood) {
                        true -> Tokens.Accent
                        false -> Tokens.Danger
                        null -> Tokens.Text
                    },
                    valueStyle = MonoTile
                )
            }
            Box(Modifier.padding(horizontal = 16.dp)) { NoteCard(state.noteTitle, state.noteBody) }
        }
    }
}

/** 40 dp pill chip: filled light when selected, outlined otherwise, as on the Trends and Activities designs. */
@Composable
fun PillChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) Tokens.Text else Color.Transparent)
            .then(if (selected) Modifier else Modifier.border(1.dp, Tokens.BorderStrong, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Tokens.AccentInk else Tokens.TextSoft, maxLines = 1)
    }
}
