package com.animesh.fitnesstracker.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.ConfirmDialog
import com.animesh.fitnesstracker.ui.components.GhostButton
import com.animesh.fitnesstracker.ui.components.PillTag
import com.animesh.fitnesstracker.ui.components.ScreenHeader
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.navigation.WorkoutSection
import com.animesh.fitnesstracker.ui.navigation.WorkoutSectionRow
import com.animesh.fitnesstracker.ui.theme.Tokens

private val WEEKDAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

@Composable
fun HistoryScreen(onOpenSession: (Long) -> Unit, onSection: (WorkoutSection) -> Unit = {}) {
    val container = appContainer()
    val vm: HistoryViewModel = viewModel { HistoryViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    var pendingDayLogDelete by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            eyebrow = "Workout · History",
            title = state.monthTitle,
            subtitle = state.subtitle.ifEmpty { null },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonthArrow(Icons.Outlined.ChevronLeft, "Previous month", enabled = true, onClick = vm::previousMonth)
                    MonthArrow(Icons.Outlined.ChevronRight, "Next month", enabled = state.canGoNext, onClick = vm::nextMonth)
                }
            },
            below = { WorkoutSectionRow(WorkoutSection.History, onSection) }
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Calendar(state, onPick = vm::select)
            when (val d = state.detail) {
                is DayDetail.Sessions -> d.sessions.forEach { s ->
                    SessionCard(s, state.selectedLabel, onEdit = { onOpenSession(s.id) }, onDelete = { pendingDelete = s.id })
                }
                DayDetail.Rest -> InfoCard("Rest day", state.selectedLabel, "Scheduled rest. Nothing to log.", onDelete = { pendingDayLogDelete = "rest day" })
                is DayDetail.Skipped -> InfoCard(
                    "Skipped", state.selectedLabel,
                    if (d.groupName.isBlank()) "The workout was skipped. The cycle moved on." else "${d.groupName} was skipped. The cycle moved on.",
                    onDelete = { pendingDayLogDelete = "skipped day" }
                )
                DayDetail.TodayEmpty -> InfoCard("Today", state.selectedLabel, "Nothing logged yet.")
                is DayDetail.None -> InfoCard(
                    "No session", state.selectedLabel,
                    if (d.future) "This day has not happened yet." else "Nothing was logged on this day."
                )
            }
        }
    }

    pendingDayLogDelete?.let { what ->
        ConfirmDialog(
            title = "Delete this $what record?",
            body = "The day is cleared in History. The routine's cycle position stays where it is.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = { vm.deleteDayLog(); pendingDayLogDelete = null },
            onDismiss = { pendingDayLogDelete = null }
        )
    }

    pendingDelete?.let { id ->
        ConfirmDialog(
            title = "Delete session?",
            body = "Every set logged in this session is removed. This cannot be undone.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = { vm.deleteSession(id); pendingDelete = null },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun MonthArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = Tokens.TextSoft, disabledContentColor = Tokens.Faint)
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun Calendar(state: HistoryState, onPick: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            WEEKDAY_LETTERS.forEach { letter ->
                Text(
                    letter,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                    color = Tokens.Dim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { cell ->
                        if (cell == null) Spacer(Modifier.weight(1f).height(48.dp))
                        else DayCellView(cell, selected = cell.epochDay == state.selectedDay, onClick = { onPick(cell.epochDay) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendItem(Tokens.Accent, "Trained")
            LegendItem(Tokens.Faint, "Rest")
            LegendItem(Tokens.Danger, "Skipped")
        }
    }
}

@Composable
private fun DayCellView(cell: DayCell, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val border = when {
        selected -> Tokens.Accent
        cell.isToday -> Tokens.Faint
        else -> Color.Transparent
    }
    val dot = when (cell.dot) {
        DotKind.TRAINED -> Tokens.Accent
        DotKind.REST -> Tokens.Faint
        DotKind.SKIPPED -> Tokens.Danger
        null -> Color.Transparent
    }
    Column(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(if (selected) Tokens.Surface else Color.Transparent)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(cell.dayOfMonth.toString(), style = MonoNumber, color = if (cell.isFuture) Tokens.Faint else Tokens.Text)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal, letterSpacing = 0.sp), color = Tokens.Muted)
    }
}

@Composable
private fun CardTitleRow(title: String, date: String, tag: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Tokens.Text,
            modifier = Modifier.weight(1f)
        )
        if (tag != null) tag()
        Text(date, style = MaterialTheme.typography.bodySmall, color = Tokens.Muted, maxLines = 1)
    }
}

@Composable
private fun SessionCard(s: SessionSummary, date: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    AppCard {
        CardTitleRow(s.title, date, tag = if (s.abandoned) ({ PillTag("Abandoned", color = Tokens.Warning, borderColor = Tokens.WarningBorder) }) else null)
        Text(s.meta, style = MonoNumber.copy(fontSize = 13.sp), color = Tokens.Muted)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            s.rows.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(r.name, style = MaterialTheme.typography.bodyMedium, color = Tokens.Text, modifier = Modifier.weight(1f))
                    val color = when (r.tone) {
                        ResultTone.HIT -> Tokens.Accent
                        ResultTone.MISSED -> Tokens.TextSoft
                        ResultTone.SKIPPED, ResultTone.NONE -> Tokens.Muted
                    }
                    Text(r.result, style = MonoNumber.copy(fontSize = 13.sp), color = color, maxLines = 1)
                }
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Edit", onEdit, Modifier.weight(1f))
            GhostButton("Delete", onDelete, Modifier.weight(1f), contentColor = Tokens.Muted)
        }
    }
}

@Composable
private fun InfoCard(title: String, date: String, body: String, onDelete: (() -> Unit)? = null) {
    AppCard {
        CardTitleRow(title, date)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
        if (onDelete != null) {
            GhostButton("Delete record", onDelete, Modifier.fillMaxWidth(), contentColor = Tokens.Muted)
        }
    }
}
