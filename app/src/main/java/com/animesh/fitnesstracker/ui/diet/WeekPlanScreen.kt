package com.animesh.fitnesstracker.ui.diet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.data.model.Meal
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.BottomActionBar
import com.animesh.fitnesstracker.ui.components.ConfirmDialog
import com.animesh.fitnesstracker.ui.components.PillTag
import com.animesh.fitnesstracker.ui.components.SecondaryButton
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.Tokens

/** The active week plan as a seven by three grid. Tapping a cell opens an in-screen picker for that slot. */
@Composable
fun WeekPlanScreen(onBack: () -> Unit, onOpenMeals: () -> Unit, onOpenReminders: () -> Unit, onOpenMeal: (Long) -> Unit) {
    val container = appContainer()
    val vm: WeekPlanViewModel = viewModel { WeekPlanViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmCopy by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = state.pick != null) { vm.closePicker() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Tokens.TextSoft)
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to today") }
            Text("DIET · WEEK PLAN", style = MaterialTheme.typography.labelMedium, color = Tokens.Muted, modifier = Modifier.padding(start = 4.dp))
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.planName, style = MaterialTheme.typography.headlineLarge, color = Tokens.Text)
            Text("Active · repeats every week · 3 meals a day", style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
        }
        val pick = state.pick
        if (pick != null) {
            CellPicker(pick, onPick = vm::pick, onLeaveEmpty = vm::leaveEmpty, onCancel = vm::closePicker)
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Spacer(Modifier.width(DAY_COLUMN))
                        MealSlot.entries.forEach { s ->
                            Text(
                                slotLabel(s).uppercase(), style = MaterialTheme.typography.labelSmall, color = Tokens.Muted,
                                textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                items(state.days, key = { it.day }) { row -> DayRow(row, onOpen = vm::openCell, onOpenMeal = onOpenMeal) }
                item {
                    Text(
                        "Tap a cell to change it. Amber dot means the meal needs prep the evening before. Today is highlighted.",
                        style = MaterialTheme.typography.bodySmall, color = Tokens.Dim, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp)
                    )
                }
            }
            BottomActionBar {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Meals", onOpenMeals, Modifier.weight(1f), height = 48)
                    SecondaryButton("Copy ${state.todayLabel} to all", { confirmCopy = true }, Modifier.weight(1.5f), height = 48)
                    SecondaryButton("Reminders", onOpenReminders, Modifier.weight(1.2f), height = 48)
                }
            }
        }
    }

    if (confirmCopy) {
        val day = state.todayLabel
        ConfirmDialog(
            "Copy $day to all days?",
            "Every other day gets $day's breakfast, lunch and dinner. Their current meals are replaced.",
            "Copy",
            onConfirm = { confirmCopy = false; vm.copyTodayToAll() }, onDismiss = { confirmCopy = false }
        )
    }
}

private val DAY_COLUMN = 44.dp

@Composable
private fun DayRow(row: WeekDayRow, onOpen: (Int, MealSlot) -> Unit, onOpenMeal: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(DAY_COLUMN), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                row.label.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.48.sp),
                color = if (row.isToday) Tokens.Accent else Tokens.Muted
            )
            Text(row.proteinText, style = MonoNumber.copy(fontSize = 10.sp, lineHeight = 12.sp), color = Tokens.Dim)
        }
        row.cells.forEach { cell ->
            WeekCellView(cell, row.isToday, Modifier.weight(1f), onClick = { onOpen(cell.day, cell.slot) }, onLongClick = { cell.mealId?.let(onOpenMeal) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeekCellView(cell: WeekCell, today: Boolean, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val filled = cell.mealId != null
    val base = modifier.fillMaxHeight().heightIn(min = 56.dp).clip(shape).testTag("week_cell_${cell.day}_${cell.slot.name.lowercase()}")
    val bordered = if (filled) {
        base.background(Tokens.Surface).border(1.dp, if (today) Tokens.AccentBorder else Tokens.Border, shape)
    } else {
        base.dashedBorder(Tokens.Faint, 10.dp)
    }
    Box(bordered.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Text(
            cell.name ?: "Empty",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
            color = if (filled) Tokens.Text else Tokens.Dim,
            maxLines = 3,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp, end = if (cell.prep) 16.dp else 10.dp, top = 8.dp, bottom = 8.dp)
        )
        if (cell.prep) {
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(6.dp).clip(CircleShape).background(Tokens.Warning))
        }
    }
}

/** One pixel dashed outline with rounded corners, for empty cells. */
private fun Modifier.dashedBorder(color: Color, radius: androidx.compose.ui.unit.Dp): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    val dash = 6.dp.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)))
    )
}

/** Lists the meals tagged for the slot, plus Leave empty and Cancel. */
@Composable
private fun CellPicker(pick: WeekPick, onPick: (Meal) -> Unit, onLeaveEmpty: () -> Unit, onCancel: () -> Unit) {
    val slot = slotLabel(pick.slot).lowercase()
    val day = WeekPlanState.DAY_LABELS[pick.day]
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Pick $slot for $day. Only meals tagged $slot are listed.", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp))
            }
            if (pick.options.isEmpty()) {
                item {
                    Text("No meals are tagged $slot yet. Add one under Diet · Meals.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted, modifier = Modifier.padding(16.dp))
                }
            }
            items(pick.options, key = { it.id }) { row ->
                AppCard(onClick = { onPick(row.meal) }, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(row.meal.name, style = MaterialTheme.typography.titleLarge, color = Tokens.Text)
                            Text(row.macrosLine, style = MonoNumber, color = Tokens.Muted)
                        }
                        if (row.meal.prepDayBefore) PillTag("Prep", color = Tokens.Warning, borderColor = Tokens.WarningBorder)
                    }
                }
            }
            item {
                val shape = RoundedCornerShape(12.dp)
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(shape).dashedBorder(Tokens.Faint, 12.dp).clickable(onClick = onLeaveEmpty),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Leave empty", style = MaterialTheme.typography.bodyMedium, color = Tokens.TextSoft)
                }
            }
        }
        BottomActionBar { SecondaryButton("Cancel", onCancel, Modifier.fillMaxWidth(), height = 48) }
    }
}

