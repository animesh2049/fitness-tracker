package com.animesh.fitnesstracker.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.domain.health.StressBands
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AccentChipButton
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.EmptyState
import com.animesh.fitnesstracker.ui.components.GhostButton
import com.animesh.fitnesstracker.ui.components.PrimaryButton
import com.animesh.fitnesstracker.ui.components.ScreenHeader
import com.animesh.fitnesstracker.ui.components.SectionLabel
import com.animesh.fitnesstracker.ui.components.StatTile
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.MonoStat
import com.animesh.fitnesstracker.ui.theme.Tokens

private val DayAxis = listOf("00", "06", "12", "18", "24").mapIndexed { i, t -> AxisLabel(i / 4f, t) }

private const val PAIR_BODY = "The watch talks to this app over Bluetooth only. No Garmin account, no Garmin Connect, no internet. " +
    "Steps, heart rate, sleep, Body Battery, stress and recorded activities are read from the watch's own files."

@Composable
fun HealthTodayScreen(
    onOpenWatch: () -> Unit,
    onOpenSleep: (Long) -> Unit,
    onOpenTrends: () -> Unit,
    onOpenActivities: () -> Unit,
    onOpenActivity: (Long) -> Unit
) {
    val container = appContainer()
    val vm: HealthTodayViewModel = viewModel { HealthTodayViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()

    val watchButton: @Composable () -> Unit = { SquareIconButton(Icons.Outlined.Watch, "Watch settings", onOpenWatch) }

    Column(Modifier.fillMaxSize()) {
        when {
            state.loading -> {
                ScreenHeader(state.eyebrow, state.title, null, trailing = watchButton)
                Spacer(Modifier.weight(1f))
            }
            state.empty -> {
                ScreenHeader(state.eyebrow, state.title, "No watch paired", trailing = watchButton)
                EmptyState("Pair your Garmin", PAIR_BODY, Modifier.weight(1f)) {
                    PrimaryButton("Pair watch", onOpenWatch, height = 48)
                }
            }
            else -> {
                ScreenHeader(state.eyebrow, state.title, state.subtitle, trailing = watchButton, below = {
                    DaySwitcher(
                        label = state.dayLabel,
                        onPrevious = vm::previousDay,
                        onNext = vm::nextDay,
                        previousEnabled = state.canGoBack,
                        nextEnabled = !state.isToday
                    )
                })
                HubBody(state, vm, onOpenWatch, onOpenSleep, onOpenTrends, onOpenActivities, onOpenActivity)
            }
        }
    }
}

@Composable
private fun HubBody(
    state: HealthTodayState,
    vm: HealthTodayViewModel,
    onOpenWatch: () -> Unit,
    onOpenSleep: (Long) -> Unit,
    onOpenTrends: () -> Unit,
    onOpenActivities: () -> Unit,
    onOpenActivity: (Long) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        state.sync?.let { strip ->
            item("sync") {
                SyncStripCard(strip, onTap = {
                    when {
                        strip.running -> Unit
                        strip.paired -> vm.syncNow()
                        else -> onOpenWatch()
                    }
                })
            }
        }
        item("tiles") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Steps", state.steps, state.stepsSub, Modifier.weight(1f), valueStyle = MonoTile)
                StatTile("Distance", state.distance, "walking", Modifier.weight(1f), valueStyle = MonoTile)
                StatTile("Active", state.kcal, "kcal", Modifier.weight(1f), valueStyle = MonoTile)
            }
        }
        item("hr") { HeartRateCard(state) }
        item("bb") { BodyBatteryCard(state) }
        item("stress") { StressCard(state) }
        item("sleep") { SleepCard(state.sleep, onClick = { onOpenSleep(state.epochDay) }) }
        item("hrv") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("HRV overnight", state.hrv, state.hrvSub, Modifier.weight(1f), valueStyle = MonoTile)
                StatTile("Intensity minutes", state.intensity, state.intensitySub, Modifier.weight(1f), valueStyle = MonoTile)
            }
        }
        item("acts_label") { SectionLabel("Activities", Modifier.padding(top = 2.dp)) }
        if (state.activities.isEmpty()) {
            item("no_acts") { DashedNotice("No activity recorded on the watch") }
        } else {
            items(state.activities, key = { "act_${it.id}" }) { a ->
                ActivityRow(a.kind, a.name, a.meta, a.duration, a.sub, linked = false, onClick = { onOpenActivity(a.id) })
            }
        }
        item("links") {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Trends", onOpenTrends, Modifier.weight(1f))
                GhostButton("All activities", onOpenActivities, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SyncStripCard(strip: SyncStrip, onTap: () -> Unit) {
    AppCard(
        borderColor = Tokens.AccentBorder,
        background = Tokens.AccentSurface,
        padding = PaddingValues(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Watch, contentDescription = null, tint = Tokens.Accent, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(strip.watchLine, style = CardTitleStyle, color = Tokens.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(strip.statusLine, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AccentChipButton(strip.buttonText, onTap)
        }
    }
}

@Composable
private fun HeartRateCard(state: HealthTodayState) {
    AppCard(padding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardHeader("Heart rate", state.hrRange, Modifier.padding(horizontal = 4.dp))
            if (state.heartRate.isEmpty) {
                DashedNotice("No heart rate readings for this day")
            } else {
                TimeCurveChart(
                    series = state.heartRate,
                    gridValues = state.hrGrid,
                    gridLabel = { it.toInt().toString() },
                    xLabels = DayAxis,
                    lineColor = HealthColors.HeartRate,
                    referenceY = state.hrResting
                )
            }
            HorizontalDivider(color = Tokens.Surface2, thickness = 1.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                FooterStat("Resting", state.hrRest)
                FooterStat("Average", state.hrAvg)
                FooterStat(state.hrMaxLabel, state.hrMax, alignEnd = true)
            }
        }
    }
}

@Composable
private fun BodyBatteryCard(state: HealthTodayState) {
    AppCard(padding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardHeader("Body Battery", state.batteryNow, Modifier.padding(horizontal = 4.dp))
            if (state.battery.isEmpty) {
                DashedNotice("No Body Battery readings for this day")
            } else {
                TimeCurveChart(
                    series = state.battery,
                    gridValues = listOf(25f, 50f, 75f),
                    gridLabel = { it.toInt().toString() },
                    xLabels = DayAxis,
                    lineColor = Tokens.Accent,
                    fill = true,
                    height = 102.dp,
                    plotTop = 8.dp,
                    plotBottom = 80.dp,
                    axisTop = 88.dp
                )
            }
            HorizontalDivider(color = Tokens.Surface2, thickness = 1.dp)
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                LabelledMono("High", state.batteryHigh)
                LabelledMono("Low", state.batteryLow)
            }
        }
    }
}

@Composable
private fun LabelledMono(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Muted)
        Text(value, style = MonoNumber.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = Tokens.Text)
    }
}

@Composable
private fun StressCard(state: HealthTodayState) {
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Stress", style = CardTitleStyle, color = Tokens.Text)
            LabelledMono("average", state.stressAvg)
        }
        BandBar(state.stressWeights, HealthColors.StressBands, height = 10)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StressBands.NAMES.forEachIndexed { i, name ->
                LegendItem(HealthColors.StressBands[i], name, state.stressHours.getOrElse(i) { "0 h" })
            }
        }
    }
}

@Composable
private fun SleepCard(sleep: SleepCardData?, onClick: () -> Unit) {
    AppCard(padding = PaddingValues(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), onClick = if (sleep != null) onClick else null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Sleep", style = CardTitleStyle, color = Tokens.Text)
                    if (sleep != null) Text(sleep.window, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Muted)
                }
                if (sleep == null) {
                    Text("No sleep recorded", style = MaterialTheme.typography.bodySmall, color = Tokens.Dim)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(sleep.duration, style = MonoStat, color = Tokens.Text)
                        sleep.scoreLabel?.let { AccentPill(it) }
                    }
                    BandBar(sleep.stageWeights, HealthColors.SleepStages, height = 8)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        sleep.stageLabels.forEachIndexed { i, (name, dur) ->
                            LegendItem(HealthColors.SleepStages[i], name, dur, gap = 5)
                        }
                    }
                }
            }
            if (sleep != null) Icon(Icons.Outlined.ChevronRight, contentDescription = "Open sleep", tint = Tokens.Dim, modifier = Modifier.size(20.dp))
        }
    }
}
