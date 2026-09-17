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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.animesh.fitnesstracker.ui.components.EmptyState
import com.animesh.fitnesstracker.ui.components.GhostButton
import com.animesh.fitnesstracker.ui.components.ProgressBar
import com.animesh.fitnesstracker.ui.components.StatTile
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.Tokens

@Composable
fun ActivityDetailScreen(activityId: Long, onBack: () -> Unit, onOpenSession: (Long) -> Unit) {
    val container = appContainer()
    val vm: ActivityDetailViewModel = viewModel(key = "activity_$activityId") { ActivityDetailViewModel(container, activityId) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        HealthBackHeader(state.eyebrow, state.title, state.subtitle, onBack)
        when {
            state.loading -> Spacer(Modifier.weight(1f))
            state.missing -> EmptyState("Activity not found", "It may have been removed with a re-import. Go back and pick another one.", Modifier.weight(1f))
            else -> DetailBody(state, onOpenSession)
        }
    }
}

@Composable
private fun DetailBody(state: ActivityDetailState, onOpenSession: (Long) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item("tiles") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.tiles.forEach { t -> StatTile(t.label, t.value, t.sub, Modifier.weight(1f), valueStyle = MonoTile) }
            }
        }
        state.linked?.let { linked ->
            item("linked") {
                AppCard(borderColor = Tokens.AccentBorder, background = Tokens.AccentSurface, padding = PaddingValues(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Link, contentDescription = null, tint = Tokens.Accent, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Linked to your session", style = CardTitleStyle, color = Tokens.Text)
                            Text(linked.summary, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Muted)
                        }
                        GhostButton("Open", { onOpenSession(linked.sessionId) }, height = 36, contentColor = Tokens.Accent, borderColor = Tokens.AccentBorder)
                    }
                }
            }
        }
        state.route?.let { route ->
            item("route") {
                AppCard(padding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CardHeader("Route", state.routeCaption.ifEmpty { null }, Modifier.padding(horizontal = 4.dp))
                        RoutePreview(route)
                        Text("Track only, no map tiles offline", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Normal), color = Tokens.Dim, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
        }
        item("hr") {
            AppCard(padding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardHeader("Heart rate", state.hrRange.ifEmpty { null }, Modifier.padding(horizontal = 4.dp))
                    if (state.heartRate.isEmpty) DashedNotice("No heart rate track in this file")
                    else TimeCurveChart(
                        series = state.heartRate,
                        gridValues = state.hrGrid,
                        gridLabel = { it.toInt().toString() },
                        xLabels = state.hrAxis,
                        lineColor = HealthColors.HeartRate
                    )
                }
            }
        }
        item("zones") {
            AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Time in zones", style = CardTitleStyle, color = Tokens.Text)
                    state.zones.forEachIndexed { i, z ->
                        Row(Modifier.fillMaxWidth().height(26.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(z.name, style = MonoNumber.copy(fontSize = 12.sp), color = Tokens.Muted, modifier = Modifier.width(22.dp))
                            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Tokens.Surface2)) {
                                Box(Modifier.fillMaxWidth(z.fraction.coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(HealthColors.Zones[i]))
                            }
                            Text(z.minutes, style = MonoNumber.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = Tokens.Text, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
                            Text(z.range, style = MonoNumber.copy(fontSize = 11.sp), color = Tokens.Dim, textAlign = TextAlign.End, modifier = Modifier.width(66.dp))
                        }
                    }
                }
            }
        }
        state.laps?.let { laps ->
            item("laps") { LapsCard(laps) }
        }
        item("effects") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.effects.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { t -> StatTile(t.label, t.value, t.sub, Modifier.weight(1f), valueStyle = MonoTile) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item("footer") {
            Text(state.footer, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp), color = Tokens.Dim, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun LapsCard(laps: List<LapRow>) {
    AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                val style = MaterialTheme.typography.labelSmall
                Text("KM", style = style, color = Tokens.Muted, modifier = Modifier.width(40.dp))
                Text("PACE", style = style, color = Tokens.Muted, modifier = Modifier.weight(1f))
                Text("AVG HR", style = style, color = Tokens.Muted, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                Text("TIME", style = style, color = Tokens.Muted, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
            }
            laps.forEach { lap ->
                HorizontalDivider(color = Tokens.Surface2, thickness = 1.dp)
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    val mono = MonoNumber.copy(fontSize = 13.sp)
                    Text(lap.km, style = mono, color = Tokens.Text, modifier = Modifier.width(40.dp))
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width((30 + 80 * lap.bar.coerceIn(0f, 1f)).dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(HealthColors.BarDim))
                        Text(lap.pace, style = mono, color = Tokens.Text)
                    }
                    Text(lap.hr, style = mono, color = Tokens.Text, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                    Text(lap.time, style = mono, color = Tokens.Muted, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                }
            }
        }
    }
}
