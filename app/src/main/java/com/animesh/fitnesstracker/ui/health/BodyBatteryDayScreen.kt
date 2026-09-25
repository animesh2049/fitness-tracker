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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.data.model.BodyBatteryKind
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.domain.health.BodyBatteryEvents
import com.animesh.fitnesstracker.domain.health.DaySummary
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.GhostButton
import com.animesh.fitnesstracker.ui.components.StatTile
import com.animesh.fitnesstracker.ui.theme.MonoNumberLarge
import com.animesh.fitnesstracker.ui.theme.Tokens
import com.animesh.fitnesstracker.util.Dates
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One event row: what it was, when, and the signed change. */
data class BodyBatteryRowUi(val kind: BodyBatteryKind, val title: String, val detail: String, val delta: String, val charged: Boolean, val neutral: Boolean = false)

data class BodyBatteryDayState(
    val loading: Boolean = true,
    val eyebrow: String = "Body Battery",
    val title: String = "",
    val subtitle: String? = null,
    val series: CurveSeries = CurveSeries.EMPTY,
    val caption: String = "",
    val charged: String = "n/a",
    val drained: String = "n/a",
    val rows: List<BodyBatteryRowUi> = emptyList(),
    /** Start, end and gain of the night that ended on the day, null when the metrics file has not given them. */
    val overnight: Triple<String, String, String>? = null
)

/** The Body Battery day screen (version 0.5): the day's curve, the watch's charged and drained events and the night's start and end. */
class BodyBatteryDayViewModel(c: AppContainer, epochDay: Long) : ViewModel() {
    val state: StateFlow<BodyBatteryDayState> = combine(c.health.observeDay(epochDay), c.activities.observeDay(epochDay), c.settings.observe()) { inputs, activities, settings ->
        val d = inputs.epochDay
        val isToday = d == Dates.todayEpochDay()
        val summary = DaySummary.compute(d, inputs.minutes, inputs.stress, inputs.restingHr?.bpm, inputs.intensityWeek, settings.stepGoal)
        val day = BodyBatteryEvents.day(inputs.bodyBatteryEvents, activities, inputs.night)
        val current = summary.bodyBatteryCurrent
        BodyBatteryDayState(
            loading = false,
            eyebrow = "Body Battery · ${HealthFormat.eyebrowDate(d)}",
            title = current?.let { if (isToday) "${it.value} now" else "${it.value} at bedtime" } ?: "No readings",
            subtitle = if (summary.bodyBatteryHigh != null && summary.bodyBatteryLow != null) {
                "High ${summary.bodyBatteryHigh.value} at ${HealthFormat.timeOfDay(summary.bodyBatteryHigh.timestamp)} · " +
                    "Low ${summary.bodyBatteryLow.value} at ${HealthFormat.timeOfDay(summary.bodyBatteryLow.timestamp)}"
            } else null,
            series = HealthTodayViewModel.batterySeries(inputs.stress, Dates.dayStartSeconds(d)),
            caption = current?.let { reserveWord(it.value) } ?: "",
            charged = if (day.chargedTotal > 0) "+${day.chargedTotal}" else "0",
            drained = if (day.drainedTotal > 0) "${HealthTodayViewModel.MINUS}${day.drainedTotal}" else "0",
            rows = day.rows.map { r ->
                BodyBatteryRowUi(
                    kind = r.event.kind, title = r.title, detail = r.detail,
                    delta = signed(r.event.delta),
                    charged = r.event.charged,
                    neutral = r.event.delta == 0
                )
            },
            overnight = if (day.overnightStart != null && day.overnightEnd != null) {
                val gain = day.overnightGain ?: (day.overnightEnd - day.overnightStart)
                Triple(day.overnightStart.toString(), day.overnightEnd.toString(), signed(gain))
            } else null
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BodyBatteryDayState())

    companion object {
        /** Garmin's reserve bands: 5 to 25 very low, 26 to 50 low, 51 to 75 medium, 76 to 100 high. */
        fun reserveWord(value: Int): String = when {
            value > 75 -> "high reserve"
            value > 50 -> "medium reserve"
            value > 25 -> "low reserve"
            else -> "very low reserve"
        }

        /** "+51", "−10" (Unicode minus), "0". */
        fun signed(delta: Int): String = when {
            delta > 0 -> "+$delta"
            delta < 0 -> "${HealthTodayViewModel.MINUS}${-delta}"
            else -> "0"
        }
    }
}

private val DayAxis = listOf("00", "06", "12", "18", "24").mapIndexed { i, t -> AxisLabel(i / 4f, t) }

@Composable
fun BodyBatteryDayScreen(epochDay: Long, onBack: () -> Unit, onOpenTrends: () -> Unit) {
    val container = appContainer()
    val vm: BodyBatteryDayViewModel = viewModel(key = "bb_$epochDay") { BodyBatteryDayViewModel(container, epochDay) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        HealthBackHeader(state.eyebrow, state.title, state.subtitle, onBack)
        if (state.loading) {
            Spacer(Modifier.weight(1f))
            return@Column
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item("curve") {
                AppCard(padding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CardHeader("Today", state.caption, Modifier.padding(horizontal = 4.dp))
                        if (state.series.isEmpty) {
                            DashedNotice("No Body Battery readings for this day")
                        } else {
                            TimeCurveChart(
                                series = state.series,
                                gridValues = listOf(25f, 50f, 75f),
                                gridLabel = { it.toInt().toString() },
                                xLabels = DayAxis,
                                lineColor = Tokens.Accent,
                                fill = true,
                                height = 140.dp,
                                plotTop = 8.dp,
                                plotBottom = 116.dp,
                                axisTop = 124.dp
                            )
                        }
                    }
                }
            }
            item("totals") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Charged", state.charged, "sleep and rest", Modifier.weight(1f), valueColor = Tokens.Accent, valueStyle = MonoTile)
                    StatTile("Drained", state.drained, "workout, stress and the day", Modifier.weight(1f), valueColor = Tokens.Danger, valueStyle = MonoTile)
                }
            }
            item("events") {
                if (state.rows.isEmpty()) {
                    DashedNotice("No Body Battery events for this day")
                } else {
                    AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                        Column {
                            state.rows.forEachIndexed { i, row ->
                                EventRowView(row)
                                if (i < state.rows.lastIndex) HorizontalDivider(color = Tokens.Surface2, thickness = 1.dp)
                            }
                        }
                    }
                }
            }
            state.overnight?.let { (start, end, gain) ->
                item("overnight") {
                    AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CardHeader("Overnight", "from the sleep file")
                            Row(Modifier.fillMaxWidth()) {
                                OvernightCell("Start", start, Tokens.Text, Modifier.weight(1f))
                                OvernightCell("End", end, Tokens.Text, Modifier.weight(1f))
                                OvernightCell("Gain", gain, if (gain.startsWith("+")) Tokens.Accent else Tokens.Danger, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            item("note") {
                NoteCard(
                    "From the watch",
                    "Body Battery and its events are computed on the Forerunner. The app reads them from the monitoring file and does not rescore anything."
                )
            }
            item("trends") { GhostButton("Body Battery trends", onOpenTrends, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun OvernightCell(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Tokens.Muted)
        Text(value, style = MonoTile, color = color)
    }
}

private fun eventIcon(kind: BodyBatteryKind): ImageVector = when (kind) {
    BodyBatteryKind.SLEEP -> Icons.Outlined.Bedtime
    BodyBatteryKind.ACTIVITY -> Icons.Outlined.FitnessCenter
    BodyBatteryKind.UNMEASURED -> Icons.Outlined.Schedule
    BodyBatteryKind.UNKNOWN -> Icons.Outlined.Timeline
}

@Composable
private fun EventRowView(row: BodyBatteryRowUi) {
    val color = when {
        row.neutral -> Tokens.Muted
        row.charged -> Tokens.Accent
        else -> Tokens.Danger
    }
    Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Tokens.Surface2), contentAlignment = Alignment.Center) {
            Icon(eventIcon(row.kind), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, color = Tokens.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.detail, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(row.delta, style = MonoNumberLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), color = color)
    }
}
